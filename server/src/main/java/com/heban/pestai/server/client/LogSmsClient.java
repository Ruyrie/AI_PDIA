package com.heban.pestai.server.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.heban.pestai.server.common.SmsScene;

/**
 * 开发环境短信：不调用任何外部服务，只把手机号、场景与验证码打印到服务器日志，便于联调。
 */
public class LogSmsClient implements SmsClient {

    private static final Logger log = LoggerFactory.getLogger(LogSmsClient.class);

    @Override
    public void sendCode(String phone, SmsScene scene, String code) {
        log.info("【开发短信】手机号 {}，场景 {}，验证码 {}（5 分钟内有效）", phone, scene, code);
    }

    @Override
    public String name() {
        return "LogSmsClient（只打印日志）";
    }
}
