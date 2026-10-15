package com.heban.pestai.server.client;

import java.util.Locale;
import java.util.function.Supplier;

import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.config.SmsProperties;

/**
 * 短信客户端的路由（sms.provider）：每次发送时按当前阿里云配置（环境变量或后台配置）选择实现。
 * <ul>
 *   <li>log：始终只打印日志；</li>
 *   <li>aliyun：始终用阿里云；配置不齐全时（如后台删除了 Key）发送失败（50001），不会悄悄改为只打印日志；</li>
 *   <li>auto（默认）：AccessKey ID、Secret、签名齐全时用阿里云，否则只打印日志 —— 运行中在后台补齐配置立即改用阿里云。</li>
 * </ul>
 */
public class RoutingSmsClient implements SmsClient {

    public static final String PROVIDER_LOG = "log";
    public static final String PROVIDER_ALIYUN = "aliyun";
    public static final String PROVIDER_AUTO = "auto";

    private final String provider;
    private final Supplier<SmsProperties.Aliyun> config;
    private final SmsClient aliyun;
    private final SmsClient log;

    public RoutingSmsClient(String provider, Supplier<SmsProperties.Aliyun> config, SmsClient aliyun, SmsClient log) {
        this.provider = normalize(provider);
        this.config = config;
        this.aliyun = aliyun;
        this.log = log;
    }

    public static String normalize(String provider) {
        String p = provider == null ? PROVIDER_AUTO : provider.trim().toLowerCase(Locale.ROOT);
        return PROVIDER_LOG.equals(p) || PROVIDER_ALIYUN.equals(p) ? p : PROVIDER_AUTO;
    }

    public String provider() {
        return provider;
    }

    /** 本次发送使用的实现 */
    public SmsClient current() {
        return switch (provider) {
            case PROVIDER_LOG -> log;
            case PROVIDER_ALIYUN -> aliyun;
            default -> config.get().isComplete() ? aliyun : log;
        };
    }

    /** 当前是否只打印日志（不发送真实短信） */
    public boolean isLogOnly() {
        return current() == log;
    }

    @Override
    public void sendCode(String phone, SmsScene scene, String code) throws SmsSendException {
        if (PROVIDER_ALIYUN.equals(provider) && !config.get().isComplete()) {
            throw new SmsSendException("sms.provider=aliyun，但阿里云 AccessKey ID / Secret / 签名未配置齐全");
        }
        current().sendCode(phone, scene, code);
    }

    @Override
    public String name() {
        return current().name();
    }
}
