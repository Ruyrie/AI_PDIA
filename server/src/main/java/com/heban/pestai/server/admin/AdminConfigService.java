package com.heban.pestai.server.admin;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.heban.pestai.server.admin.vo.ConfigItemVO;
import com.heban.pestai.server.admin.vo.ConfigTestVO;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.config.RuntimeConfigItem;
import com.heban.pestai.server.config.RuntimeConfigService;

/**
 * 系统配置（docs/后台管理接口.md「四、系统配置」）：列出、修改、恢复为 .env / 默认值、测试。
 * <ul>
 *   <li>密钥类只返回掩码（前 3 位 + **** + 后 4 位；不足 12 位只返回 ****，避免短值几乎全部露出）；</li>
 *   <li>修改 / 恢复密钥类必须提交当前管理员密码（错误 40001「管理员密码不正确」）；</li>
 *   <li>值去掉首尾空白后不能为空，不能含换行等控制字符；开关项（AI_FALLBACK_ENABLED）只能是 true / false（不区分大小写，保存为小写）；</li>
 *   <li>操作日志：非密钥类记录新值，密钥类只记录「已更新」，不记录明文。</li>
 * </ul>
 */
@Service
public class AdminConfigService {

    private static final Logger log = LoggerFactory.getLogger(AdminConfigService.class);

    static final String MASK = "****";
    static final int MASK_MIN_LENGTH = 12;

    private final RuntimeConfigService runtimeConfig;
    private final AdminAuthService authService;
    private final AdminLogService logService;
    private final ConfigTester tester;

    public AdminConfigService(RuntimeConfigService runtimeConfig, AdminAuthService authService,
                              AdminLogService logService, ConfigTester tester) {
        this.runtimeConfig = runtimeConfig;
        this.authService = authService;
        this.logService = logService;
        this.tester = tester;
    }

    public List<ConfigItemVO> list() {
        return runtimeConfig.entries().stream().map(AdminConfigService::toVO).toList();
    }

    public ConfigItemVO update(String key, String value, String adminPassword) {
        RuntimeConfigItem item = requireItem(key);
        if (item.secret()) {
            authService.verifyCurrentPassword(adminPassword);
        }
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            throw BizException.param("配置值不能为空");
        }
        if (trimmed.chars().anyMatch(Character::isISOControl)) {
            throw BizException.param("配置值不能包含换行等控制字符");
        }
        if (item.isSwitch()) {
            trimmed = trimmed.toLowerCase(Locale.ROOT);
            if (!"true".equals(trimmed) && !"false".equals(trimmed)) {
                throw BizException.param(item.key() + " 只能是 true 或 false");
            }
        }
        runtimeConfig.set(item, trimmed, AdminContext.require().username());
        logService.log(AdminActions.CONFIG_UPDATE, "配置 " + item.key(),
                item.secret() ? "已更新（密钥类，值不记录）" : "新值：" + trimmed);
        log.info("后台修改配置 {}（{}）", item.key(), item.secret() ? "密钥类" : trimmed);
        return toVO(runtimeConfig.entry(item));
    }

    public ConfigItemVO reset(String key, String adminPassword) {
        RuntimeConfigItem item = requireItem(key);
        if (item.secret()) {
            authService.verifyCurrentPassword(adminPassword);
        }
        runtimeConfig.reset(item);
        RuntimeConfigService.Entry entry = runtimeConfig.entry(item);
        logService.log(AdminActions.CONFIG_RESET, "配置 " + item.key(), "恢复为 " + sourceLabel(entry.source()));
        log.info("后台把配置 {} 恢复为环境变量 / 默认值", item.key());
        return toVO(entry);
    }

    public ConfigTestVO test(String group, String phone) {
        if (!RuntimeConfigItem.Group.isValid(group)) {
            throw BizException.param("未知的配置分组：" + group);
        }
        ConfigTestVO result = tester.test(group, phone);
        logService.log(AdminActions.CONFIG_TEST, "配置分组 " + group,
                (result.ok() ? "成功" : "失败") + "，耗时 " + result.latencyMs() + " ms："
                        + ConfigTester.summary(result.message(), null));
        return result;
    }

    private static RuntimeConfigItem requireItem(String key) {
        return RuntimeConfigItem.of(key).orElseThrow(() -> BizException.param("未知的配置项：" + key));
    }

    static ConfigItemVO toVO(RuntimeConfigService.Entry entry) {
        RuntimeConfigItem item = entry.item();
        String value = entry.value() == null ? "" : entry.value();
        return new ConfigItemVO(item.key(), item.label(), item.group(), item.secret(),
                item.secret() ? mask(value) : value, entry.source().name(), entry.updatedAt(), entry.updatedBy());
    }

    /** 前 3 位 + **** + 后 4 位；不足 12 位只返回 ****；空值返回空串 */
    static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() < MASK_MIN_LENGTH) {
            return MASK;
        }
        return value.substring(0, 3) + MASK + value.substring(value.length() - 4);
    }

    private static String sourceLabel(RuntimeConfigService.Source source) {
        return switch (source) {
            case ENV -> "环境变量 / .env 的值";
            case DEFAULT -> "默认值";
            case NONE -> "未配置";
            case ADMIN -> "后台设置";
        };
    }
}
