package com.heban.pestai.server.config;

import java.util.Optional;

/**
 * 可在后台修改、修改后立即生效的配置项（docs/后台管理接口.md「四、系统配置」「八、模型自动备用」）。
 * <p>
 * 枚举名就是配置 key，与环境变量 / .env 中的变量同名；property 为 application.yml 中绑定该变量的属性名，
 * 「环境变量 / .env / 默认值」这一层的值按属性名从 Spring Environment 读取（与原来的 *Properties 绑定结果一致，
 * 测试中用 @SpringBootTest(properties = "vision.api-key=") 覆盖的值同样生效）。
 * defaultValue 与 application.yml 中 ${VAR:默认值} 的默认值一致，只用于判断来源是 ENV 还是 DEFAULT。
 */
public enum RuntimeConfigItem {

    DASHSCOPE_API_KEY(Group.VISION, "DashScope API Key（识图与语音识别共用）", true, "vision.api-key", ""),
    VISION_MODEL(Group.VISION, "识图模型", false, "vision.model", "qwen3.8-max"),
    DEEPSEEK_API_KEY(Group.CHAT, "DeepSeek API Key", true, "chat.api-key", ""),
    CHAT_MODEL(Group.CHAT, "问答模型", false, "chat.model", "deepseek-flash"),
    ASR_MODEL(Group.ASR, "语音识别模型", false, "asr.model", "qwen3-asr-flash"),
    ALIYUN_SMS_ACCESS_KEY_ID(Group.SMS, "AccessKey ID", true, "sms.aliyun.access-key-id", ""),
    ALIYUN_SMS_ACCESS_KEY_SECRET(Group.SMS, "AccessKey Secret", true, "sms.aliyun.access-key-secret", ""),
    ALIYUN_SMS_SIGN_NAME(Group.SMS, "短信签名", false, "sms.aliyun.sign-name", ""),
    ALIYUN_SMS_TEMPLATE_CODE(Group.SMS, "登录 / 注册模板（兜底模板）", false, "sms.aliyun.template-code", "100001"),
    ALIYUN_SMS_TEMPLATE_RESET(Group.SMS, "重置密码模板", false, "sms.aliyun.template-reset", "100003"),
    ALIYUN_SMS_TEMPLATE_REBIND(Group.SMS, "找回账号（绑定新手机号）模板", false, "sms.aliyun.template-rebind", "100004"),
    ALIYUN_SMS_TEMPLATE_CHANGE_PHONE(Group.SMS, "更换手机号模板", false, "sms.aliyun.template-change-phone", "100002"),
    ALIYUN_SMS_TEMPLATE_VERIFY(Group.SMS, "注销账号 / 生成恢复码模板", false, "sms.aliyun.template-verify", "100005"),
    AI_FALLBACK_ENABLED(Group.FALLBACK, "启用自动备用（true / false）", false, "ai-fallback.enabled", "true"),
    CHAT_FALLBACK_MODEL(Group.FALLBACK, "问答备用模型（通义千问，用 DASHSCOPE_API_KEY）", false, "ai-fallback.chat-model",
            "qwen3.8-max"),
    VISION_FALLBACK_MODEL(Group.FALLBACK, "识图备用模型（DeepSeek，用 DEEPSEEK_API_KEY）", false, "ai-fallback.vision-model",
            "deepseek-flash");

    /** 分组（后台按组显示，配置测试也按组进行） */
    public static final class Group {
        public static final String VISION = "vision";
        public static final String CHAT = "chat";
        public static final String ASR = "asr";
        public static final String SMS = "sms";
        /** 模型自动备用「自动备用」：开关与两个备用模型名 */
        public static final String FALLBACK = "fallback";

        private Group() {
        }

        public static boolean isValid(String group) {
            return VISION.equals(group) || CHAT.equals(group) || ASR.equals(group) || SMS.equals(group)
                    || FALLBACK.equals(group);
        }
    }

    private final String group;
    private final String label;
    private final boolean secret;
    private final String property;
    private final String defaultValue;

    RuntimeConfigItem(String group, String label, boolean secret, String property, String defaultValue) {
        this.group = group;
        this.label = label;
        this.secret = secret;
        this.property = property;
        this.defaultValue = defaultValue;
    }

    /** 配置 key（= 环境变量名） */
    public String key() {
        return name();
    }

    public String group() {
        return group;
    }

    public String label() {
        return label;
    }

    /** 密钥类：AES-GCM 加密保存，后台只显示掩码，修改 / 恢复必须提交管理员密码 */
    public boolean secret() {
        return secret;
    }

    public String property() {
        return property;
    }

    public String defaultValue() {
        return defaultValue;
    }

    /** 开关项：后台只接受 true / false（AdminConfigService 校验），读取见 RuntimeConfigService.isOn */
    public boolean isSwitch() {
        return this == AI_FALLBACK_ENABLED;
    }

    /** 按 key 查找（区分大小写）；未知 key 返回空 */
    public static Optional<RuntimeConfigItem> of(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (RuntimeConfigItem item : values()) {
            if (item.name().equals(key)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }
}
