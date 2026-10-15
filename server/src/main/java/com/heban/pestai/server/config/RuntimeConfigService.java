package com.heban.pestai.server.config;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.heban.pestai.server.entity.SystemConfig;
import com.heban.pestai.server.mapper.SystemConfigMapper;
import com.heban.pestai.server.security.JwtTokenProvider;

/**
 * 运行时配置（API Key、模型名、短信签名与模板）：生效顺序 后台设置的值（t_system_config）> 环境变量 / .env > 默认值，
 * 修改后立即生效、不用重启。识图、问答、语音识别、短信客户端每次请求都通过本类取 Key 与模型（见 ClientConfig）。
 * <ul>
 *   <li>环境变量 / .env / 默认值：按 RuntimeConfigItem.property() 从 Spring Environment 读取（与 *Properties 绑定结果一致），
 *       启动后不会变化，构造时读入；</li>
 *   <li>后台设置的值：首次使用时整体读入内存，之后只在后台修改 / 恢复时重新读取（单机部署）。读取失败（如还没有执行迁移 006、
 *       数据库暂时不可用）时记录告警、按「没有后台设置」处理，60 秒后再试；</li>
 *   <li>密钥类加密保存（ConfigCipher）；解密失败（如更换了 PESTAI_CONFIG_SECRET / JWT_SECRET）时记录告警并按未设置处理。</li>
 * </ul>
 * 没有任何后台设置时，各客户端的行为与原来完全相同。
 */
@Service
public class RuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigService.class);

    static final long RETRY_MILLIS = 60_000;

    /** 值的来源 */
    public enum Source { ADMIN, ENV, DEFAULT, NONE }

    /** 一项配置的当前状态（value 为明文，只供服务端使用；对外显示时密钥类必须掩码） */
    public record Entry(RuntimeConfigItem item, String value, Source source, LocalDateTime updatedAt, String updatedBy) {
    }

    /** 后台设置的一项（已解密） */
    record AdminValue(String value, String updatedBy, LocalDateTime updatedAt) {
    }

    private record Snapshot(Map<RuntimeConfigItem, AdminValue> overrides, boolean loaded) {
    }

    private final SystemConfigMapper mapper;
    private final ConfigCipher cipher;
    private final Clock clock;
    private final Map<RuntimeConfigItem, String> baseValues = new EnumMap<>(RuntimeConfigItem.class);
    private final Map<RuntimeConfigItem, Source> baseSources = new EnumMap<>(RuntimeConfigItem.class);

    private volatile Snapshot snapshot;
    private volatile long retryAtMillis;
    private boolean loadFailureLogged;

    @Autowired
    public RuntimeConfigService(SystemConfigMapper mapper, Environment environment, PestaiProperties properties,
                                Clock clock) {
        this(mapper, environment, new ConfigCipher(properties.getConfigSecret(),
                JwtTokenProvider.effectiveSecret(properties.getJwt().getSecret())), clock);
    }

    public RuntimeConfigService(SystemConfigMapper mapper, Environment environment, ConfigCipher cipher, Clock clock) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.clock = clock;
        for (RuntimeConfigItem item : RuntimeConfigItem.values()) {
            String value = environment.getProperty(item.property());
            if (value == null) {
                value = item.defaultValue();
            }
            value = value.trim();
            baseValues.put(item, value);
            baseSources.put(item, baseSource(item, value, environment));
        }
    }

    /**
     * 来源判断：值为空 → NONE；环境变量 / .env 中有同名的非空变量 → ENV；与默认值相同 → DEFAULT；
     * 其他（如 -Dvision.model=xxx 直接设置属性）→ ENV。
     */
    private static Source baseSource(RuntimeConfigItem item, String value, Environment environment) {
        if (value.isEmpty()) {
            return Source.NONE;
        }
        String env = environment.getProperty(item.key());
        if (env != null && !env.isBlank()) {
            return Source.ENV;
        }
        return value.equals(item.defaultValue()) ? Source.DEFAULT : Source.ENV;
    }

    public ConfigCipher cipher() {
        return cipher;
    }

    /** 当前生效的值（去掉首尾空白；未配置为空串，不为 null） */
    public String get(RuntimeConfigItem item) {
        AdminValue adminValue = overrides().get(item);
        return adminValue != null ? adminValue.value() : baseValues.get(item);
    }

    public Entry entry(RuntimeConfigItem item) {
        AdminValue adminValue = overrides().get(item);
        if (adminValue != null) {
            return new Entry(item, adminValue.value(), Source.ADMIN, adminValue.updatedAt(), adminValue.updatedBy());
        }
        return new Entry(item, baseValues.get(item), baseSources.get(item), null, null);
    }

    public List<Entry> entries() {
        List<Entry> list = new ArrayList<>();
        for (RuntimeConfigItem item : RuntimeConfigItem.values()) {
            list.add(entry(item));
        }
        return list;
    }

    /**
     * 开关项是否打开（AI_FALLBACK_ENABLED）：为空时取默认值；false / 0 / no / off（不区分大小写）为关闭，其他为打开。
     * 后台只能保存 true / false，其他写法只可能来自环境变量 / .env。
     */
    public boolean isOn(RuntimeConfigItem item) {
        String value = get(item);
        if (value.isEmpty()) {
            value = item.defaultValue();
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "false", "0", "no", "off" -> false;
            default -> true;
        };
    }

    /** 模型自动备用是否打开（AI_FALLBACK_ENABLED，默认 true） */
    public boolean fallbackEnabled() {
        return isOn(RuntimeConfigItem.AI_FALLBACK_ENABLED);
    }

    /** 不看后台设置时的值（环境变量 / .env / 默认值） */
    public String baseValue(RuntimeConfigItem item) {
        return baseValues.get(item);
    }

    /** 后台设置：密钥类加密后保存；保存后立即重新读取（数据库错误原样抛出，由调用方转为 50001） */
    public void set(RuntimeConfigItem item, String value, String updatedBy) {
        SystemConfig row = new SystemConfig();
        row.setConfigKey(item.key());
        row.setConfigValue(item.secret() ? cipher.encrypt(value) : value);
        row.setSecret(item.secret());
        row.setUpdatedBy(updatedBy);
        row.setUpdatedAt(LocalDateTime.now(clock));
        mapper.upsert(row);
        reload();
    }

    /** 恢复为环境变量 / 默认值 */
    public void reset(RuntimeConfigItem item) {
        mapper.deleteByKey(item.key());
        reload();
    }

    /** 重新读取后台设置；失败时抛出（set / reset 之后调用，调用方需要知道是否成功） */
    public synchronized void reload() {
        snapshot = new Snapshot(load(), true);
        loadFailureLogged = false;
    }

    /**
     * 短信配置快照：基础值（端点、有效分钟数等）取自 SmsProperties，AccessKey、签名、模板取当前生效的值。
     * AliyunSmsClient 每次发送时取一次。
     */
    public SmsProperties.Aliyun smsAliyun(SmsProperties.Aliyun base) {
        SmsProperties.Aliyun c = new SmsProperties.Aliyun();
        c.setEndpoint(base.getEndpoint());
        c.setValidMinutes(base.getValidMinutes());
        c.setAccessKeyId(get(RuntimeConfigItem.ALIYUN_SMS_ACCESS_KEY_ID));
        c.setAccessKeySecret(get(RuntimeConfigItem.ALIYUN_SMS_ACCESS_KEY_SECRET));
        c.setSignName(get(RuntimeConfigItem.ALIYUN_SMS_SIGN_NAME));
        c.setTemplateCode(get(RuntimeConfigItem.ALIYUN_SMS_TEMPLATE_CODE));
        c.setTemplateReset(get(RuntimeConfigItem.ALIYUN_SMS_TEMPLATE_RESET));
        c.setTemplateRebind(get(RuntimeConfigItem.ALIYUN_SMS_TEMPLATE_REBIND));
        c.setTemplateChangePhone(get(RuntimeConfigItem.ALIYUN_SMS_TEMPLATE_CHANGE_PHONE));
        c.setTemplateVerify(get(RuntimeConfigItem.ALIYUN_SMS_TEMPLATE_VERIFY));
        return c;
    }

    /** 当前缓存；未读取过或上次读取失败且已过 60 秒时（重新）读取，失败按「没有后台设置」处理 */
    private Map<RuntimeConfigItem, AdminValue> overrides() {
        Snapshot current = snapshot;
        if (current != null && (current.loaded() || clock.millis() < retryAtMillis)) {
            return current.overrides();
        }
        synchronized (this) {
            current = snapshot;
            if (current != null && (current.loaded() || clock.millis() < retryAtMillis)) {
                return current.overrides();
            }
            try {
                snapshot = new Snapshot(load(), true);
                loadFailureLogged = false;
            } catch (RuntimeException e) {
                if (!loadFailureLogged) {
                    log.warn("读取后台配置 t_system_config 失败（是否已执行 sql/migrations/006_admin.sql？），"
                            + "暂按环境变量 / .env 配置运行，60 秒后重试：{}", rootMessage(e));
                    loadFailureLogged = true;
                } else {
                    log.debug("读取后台配置 t_system_config 仍然失败：{}", rootMessage(e));
                }
                snapshot = new Snapshot(Collections.emptyMap(), false);
                retryAtMillis = clock.millis() + RETRY_MILLIS;
            }
            return snapshot.overrides();
        }
    }

    private Map<RuntimeConfigItem, AdminValue> load() {
        Map<RuntimeConfigItem, AdminValue> map = new EnumMap<>(RuntimeConfigItem.class);
        List<SystemConfig> rows = mapper.selectAll();
        if (rows == null) {
            return map;
        }
        for (SystemConfig row : rows) {
            RuntimeConfigItem item = RuntimeConfigItem.of(row.getConfigKey()).orElse(null);
            if (item == null) {
                log.debug("忽略未知的后台配置项 {}", row.getConfigKey());
                continue;
            }
            String value = row.getConfigValue();
            if (Boolean.TRUE.equals(row.getSecret()) || item.secret()) {
                try {
                    value = cipher.decrypt(value);
                } catch (GeneralSecurityException | RuntimeException e) {
                    log.warn("后台配置 {} 解密失败（可能更换了 PESTAI_CONFIG_SECRET 或 JWT_SECRET），按未设置处理，请在后台重新填写",
                            item.key());
                    continue;
                }
            }
            if (value == null || value.isBlank()) {
                continue;
            }
            map.put(item, new AdminValue(value.trim(), row.getUpdatedBy(), row.getUpdatedAt()));
        }
        return map;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
