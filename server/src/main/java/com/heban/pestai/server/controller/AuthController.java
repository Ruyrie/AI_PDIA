package com.heban.pestai.server.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heban.pestai.server.common.ApiResponse;
import com.heban.pestai.server.request.PasswordLoginRequest;
import com.heban.pestai.server.request.PasswordResetRequest;
import com.heban.pestai.server.request.PasswordVerifyRequest;
import com.heban.pestai.server.request.RecoverRebindRequest;
import com.heban.pestai.server.request.RecoverVerifyRequest;
import com.heban.pestai.server.request.RegisterRequest;
import com.heban.pestai.server.request.SmsLoginRequest;
import com.heban.pestai.server.security.UserContext;
import com.heban.pestai.server.service.AuthService;
import com.heban.pestai.server.service.PasswordResetService;
import com.heban.pestai.server.service.RecoverService;
import com.heban.pestai.server.vo.LoginVO;
import com.heban.pestai.server.vo.RebindTicketVO;
import com.heban.pestai.server.vo.ResetTicketVO;

/**
 * 登录、注册、找回密码、找回账号（白名单接口）与退出登录（需要登录）。
 */
@RestController
@RequestMapping("auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final RecoverService recoverService;

    public AuthController(AuthService authService, PasswordResetService passwordResetService,
                          RecoverService recoverService) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.recoverService = recoverService;
    }

    /** POST auth/login/password */
    @PostMapping("login/password")
    public ApiResponse<LoginVO> loginByPassword(@Valid @RequestBody PasswordLoginRequest request) {
        return ApiResponse.success(authService.loginByPassword(request.getPhone(), request.getPassword()));
    }

    /** POST auth/login/sms：未注册时自动注册，is_new 为 true */
    @PostMapping("login/sms")
    public ApiResponse<LoginVO> loginBySms(@Valid @RequestBody SmsLoginRequest request) {
        return ApiResponse.success(authService.loginBySms(request.getPhone(), request.getCode()));
    }

    /** POST auth/register */
    @PostMapping("register")
    public ApiResponse<LoginVO> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request.getPhone(), request.getCode(), request.getPassword()));
    }

    /** POST auth/password/verify：校验验证码，换取一次性 reset_ticket */
    @PostMapping("password/verify")
    public ApiResponse<ResetTicketVO> verifyResetCode(@Valid @RequestBody PasswordVerifyRequest request) {
        return ApiResponse.success(passwordResetService.verify(request.getPhone(), request.getCode()));
    }

    /** POST auth/password/reset */
    @PostMapping("password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.reset(request.getPhone(), request.getResetTicket(), request.getNewPassword());
        return ApiResponse.success();
    }

    /** POST auth/recover/verify：原手机号 + 登录密码 / 恢复码验证身份，换取一次性 rebind_ticket */
    @PostMapping("recover/verify")
    public ApiResponse<RebindTicketVO> verifyRecover(@Valid @RequestBody RecoverVerifyRequest request) {
        return ApiResponse.success(recoverService.verify(request.getOldPhone(), request.getMethod(),
                request.getPassword(), request.getRecoveryCode()));
    }

    /** POST auth/recover/rebind：凭证 + 新手机号验证码，换绑并直接登录（返回新令牌，其他设备失效） */
    @PostMapping("recover/rebind")
    public ApiResponse<LoginVO> rebind(@Valid @RequestBody RecoverRebindRequest request) {
        return ApiResponse.success(recoverService.rebind(request.getOldPhone(), request.getRebindTicket(),
                request.getNewPhone(), request.getCode(), request.getNewPassword()));
    }

    /** POST auth/logout：token_version + 1（需要登录） */
    @PostMapping("logout")
    public ApiResponse<Void> logout() {
        authService.logout(UserContext.requireUserId());
        return ApiResponse.success();
    }
}
