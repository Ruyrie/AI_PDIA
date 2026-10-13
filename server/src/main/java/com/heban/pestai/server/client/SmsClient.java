package com.heban.pestai.server.client;

import com.heban.pestai.server.common.SmsScene;

/**
 * 短信发送：LogSmsClient（开发，只打印日志）/ AliyunSmsClient（阿里云号码认证服务 · 短信认证）。
 * 验证码的生成、限频、入库由 SmsService 负责。
 */
public interface SmsClient {

    /** 发送验证码短信（模板变量 code、min） */
    void sendCode(String phone, SmsScene scene, String code) throws SmsSendException;

    /** 实现名称，用于启动日志 */
    String name();

    class SmsSendException extends Exception {

        /** 失败类型：频控（转 42901）、手机号非法（转 40001）、其他（转 50001） */
        public enum Kind { FREQUENCY, INVALID_PHONE, OTHER }

        private final Kind kind;

        public SmsSendException(String message) {
            this(Kind.OTHER, message);
        }

        public SmsSendException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public SmsSendException(String message, Throwable cause) {
            super(message, cause);
            this.kind = Kind.OTHER;
        }

        public Kind getKind() {
            return kind;
        }
    }
}
