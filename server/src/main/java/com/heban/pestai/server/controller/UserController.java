package com.heban.pestai.server.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.heban.pestai.server.common.ApiResponse;
import com.heban.pestai.server.request.ChangePhoneRequest;
import com.heban.pestai.server.request.ChangePhoneSmsRequest;
import com.heban.pestai.server.request.DeactivateRequest;
import com.heban.pestai.server.request.ProfileRequest;
import com.heban.pestai.server.request.RecoveryCodeRequest;
import com.heban.pestai.server.security.UserContext;
import com.heban.pestai.server.service.AccountService;
import com.heban.pestai.server.service.RecoveryCodeService;
import com.heban.pestai.server.service.StatsService;
import com.heban.pestai.server.service.UserService;
import com.heban.pestai.server.vo.LoginVO;
import com.heban.pestai.server.vo.RecoveryCodeVO;
import com.heban.pestai.server.vo.UploadVO;
import com.heban.pestai.server.vo.UserStatsVO;
import com.heban.pestai.server.vo.UserVO;

/** 当前用户：资料、头像、我的页统计、更换手机号、注销账号、账号恢复码（需要登录；用户 ID 只取自令牌） */
@RestController
@RequestMapping("user")
public class UserController {

    private final UserService userService;
    private final StatsService statsService;
    private final AccountService accountService;
    private final RecoveryCodeService recoveryCodeService;

    public UserController(UserService userService, StatsService statsService, AccountService accountService,
                          RecoveryCodeService recoveryCodeService) {
        this.userService = userService;
        this.statsService = statsService;
        this.accountService = accountService;
        this.recoveryCodeService = recoveryCodeService;
    }

    /** GET user/me */
    @GetMapping("me")
    public ApiResponse<UserVO> me() {
        return ApiResponse.success(userService.getCurrentUser(UserContext.requireUserId()));
    }

    /** PUT user/profile：完善 / 修改资料 */
    @PutMapping("profile")
    public ApiResponse<UserVO> updateProfile(@Valid @RequestBody ProfileRequest request) {
        return ApiResponse.success(userService.updateProfile(UserContext.requireUserId(), request));
    }

    /** POST user/avatar（multipart，字段 avatar） */
    @PostMapping("avatar")
    public ApiResponse<UploadVO> uploadAvatar(@RequestPart("avatar") MultipartFile avatar) {
        UserContext.requireUserId();
        return ApiResponse.success(userService.uploadAvatar(avatar));
    }

    /** GET user/stats：我的页统计 */
    @GetMapping("stats")
    public ApiResponse<UserStatsVO> stats() {
        return ApiResponse.success(statsService.getStats(UserContext.requireUserId()));
    }

    /** POST user/phone/sms：向新手机号发送更换手机号验证码；与当前相同 40001，已注册 40901，限频 42901 */
    @PostMapping("phone/sms")
    public ApiResponse<Void> sendChangePhoneCode(@Valid @RequestBody ChangePhoneSmsRequest request) {
        accountService.sendChangePhoneCode(UserContext.requireUserId(), request.getNewPhone());
        return ApiResponse.success();
    }

    /** PUT user/phone：验证新手机号后换绑；返回按新 token_version 签发的 LoginVO（is_new = false），其他设备登录失效 */
    @PutMapping("phone")
    public ApiResponse<LoginVO> changePhone(@Valid @RequestBody ChangePhoneRequest request) {
        return ApiResponse.success(accountService.changePhone(UserContext.requireUserId(),
                request.getNewPhone(), request.getCode()));
    }

    /** POST user/deactivate/sms：请求体 {}（可省略）；验证码发往当前账号手机号 */
    @PostMapping("deactivate/sms")
    public ApiResponse<Void> sendDeactivateCode() {
        accountService.sendDeactivateCode(UserContext.requireUserId());
        return ApiResponse.success();
    }

    /** POST user/deactivate：验证当前手机号后立即注销，账号与全部数据删除且无法恢复 */
    @PostMapping("deactivate")
    public ApiResponse<Void> deactivate(@Valid @RequestBody DeactivateRequest request) {
        accountService.deactivate(UserContext.requireUserId(), request.getCode());
        return ApiResponse.success();
    }

    /** GET user/recovery-code：恢复码状态（generated、created_at；recovery_code 恒为 null） */
    @GetMapping("recovery-code")
    public ApiResponse<RecoveryCodeVO> recoveryCodeStatus() {
        return ApiResponse.success(recoveryCodeService.status(UserContext.requireUserId()));
    }

    /** POST user/recovery-code/sms：请求体 {}（可省略）；验证码（RECOVERY）发往当前账号手机号；42901 */
    @PostMapping("recovery-code/sms")
    public ApiResponse<Void> sendRecoveryCodeSms() {
        recoveryCodeService.sendCode(UserContext.requireUserId());
        return ApiResponse.success();
    }

    /**
     * POST user/recovery-code：生成 / 重新生成恢复码，明文只在这次响应中返回。
     * 带 code 时校验当前手机号的验证码（40103）；不带时只允许 10 分钟内刚通过短信验证的用户（否则 40001）。
     */
    @PostMapping("recovery-code")
    public ApiResponse<RecoveryCodeVO> generateRecoveryCode(
            @Valid @RequestBody(required = false) RecoveryCodeRequest request) {
        String code = request == null ? null : request.getCode();
        return ApiResponse.success(recoveryCodeService.generate(UserContext.requireUserId(), code));
    }
}
