package com.heban.pestai.server.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heban.pestai.server.common.ApiResponse;
import com.heban.pestai.server.request.SmsSendRequest;
import com.heban.pestai.server.service.SmsService;

/** 发送验证码（白名单接口） */
@RestController
@RequestMapping("auth/sms")
public class SmsController {

    private final SmsService smsService;

    public SmsController(SmsService smsService) {
        this.smsService = smsService;
    }

    /**
     * POST auth/sms/send：phone、scene（LOGIN / REGISTER / RESET / REBIND；REBIND 时 phone 为找回账号的新手机号）。
     * CHANGE_PHONE、DEACTIVATE、RECOVERY 由请求校验拒绝（40001），只能通过 user/phone/sms、user/deactivate/sms、
     * user/recovery-code/sms 发送。
     */
    @PostMapping("send")
    public ApiResponse<Void> send(@Valid @RequestBody SmsSendRequest request) {
        smsService.send(request.getPhone(), request.getScene());
        return ApiResponse.success();
    }
}
