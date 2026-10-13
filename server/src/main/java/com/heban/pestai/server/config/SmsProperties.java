package com.heban.pestai.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.heban.pestai.server.common.SmsScene;

/**
 * 短信配置 sms.*。provider：auto（阿里云 AccessKey 与签名齐全时用阿里云，否则只打印日志）、log、aliyun。
 * 阿里云使用「号码认证服务 · 短信认证」（Dypnsapi SendSmsVerifyCode），赠送签名必须搭配赠送模板。
 */
@ConfigurationProperties(prefix = "sms")
public class SmsProperties {

    private String provider = "auto";
    private final Aliyun aliyun = new Aliyun();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Aliyun getAliyun() {
        return aliyun;
    }

    public static class Aliyun {
        private String accessKeyId = "";
        private String accessKeySecret = "";
        /** 赠送签名，如「恒创联众」 */
        private String signName = "";
        /** 默认模板（登录 / 注册模板 100001），也是未单独配置的场景的兜底模板；变量 ${code}、${min} */
        private String templateCode = "100001";
        /** 找回密码：重置密码模板 */
        private String templateReset = "100003";
        /** 找回账号验证新手机号：绑定新手机号模板 */
        private String templateRebind = "100004";
        /** 更换手机号（验证码发往新手机号）：修改绑定手机号模板 */
        private String templateChangePhone = "100002";
        /** 注销账号、生成账号恢复码（验证码都发往当前手机号）：验证绑定手机号模板 */
        private String templateVerify = "100005";
        /** 模板变量 ${min}：验证码有效分钟数，与 SmsService 的 5 分钟一致 */
        private int validMinutes = 5;
        private String endpoint = "https://dypnsapi.aliyuncs.com/";

        public boolean isComplete() {
            return notBlank(accessKeyId) && notBlank(accessKeySecret) && notBlank(signName) && notBlank(templateCode);
        }

        /**
         * 按场景选模板：登录 / 注册 → 默认模板，找回密码 → 重置密码模板，找回账号 → 绑定新手机号模板，
         * 更换手机号 → 修改绑定手机号模板，注销账号 / 生成恢复码 → 验证绑定手机号模板；某场景模板配置为空时用默认模板兜底。
         * 不写 default 分支：新增场景时编译器会提示在这里补上映射。
         */
        public String templateFor(SmsScene scene) {
            String code = switch (scene) {
                case LOGIN, REGISTER -> templateCode;
                case RESET -> templateReset;
                case REBIND -> templateRebind;
                case CHANGE_PHONE -> templateChangePhone;
                case DEACTIVATE, RECOVERY -> templateVerify;
            };
            return notBlank(code) ? code : templateCode;
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public void setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
        }

        public String getAccessKeySecret() {
            return accessKeySecret;
        }

        public void setAccessKeySecret(String accessKeySecret) {
            this.accessKeySecret = accessKeySecret;
        }

        public String getSignName() {
            return signName;
        }

        public void setSignName(String signName) {
            this.signName = signName;
        }

        public String getTemplateCode() {
            return templateCode;
        }

        public void setTemplateCode(String templateCode) {
            this.templateCode = templateCode;
        }

        public String getTemplateReset() {
            return templateReset;
        }

        public void setTemplateReset(String templateReset) {
            this.templateReset = templateReset;
        }

        public String getTemplateRebind() {
            return templateRebind;
        }

        public void setTemplateRebind(String templateRebind) {
            this.templateRebind = templateRebind;
        }

        public String getTemplateChangePhone() {
            return templateChangePhone;
        }

        public void setTemplateChangePhone(String templateChangePhone) {
            this.templateChangePhone = templateChangePhone;
        }

        public String getTemplateVerify() {
            return templateVerify;
        }

        public void setTemplateVerify(String templateVerify) {
            this.templateVerify = templateVerify;
        }

        public int getValidMinutes() {
            return validMinutes;
        }

        public void setValidMinutes(int validMinutes) {
            this.validMinutes = validMinutes;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
    }
}
