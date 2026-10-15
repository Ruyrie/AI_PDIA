package com.heban.pestai.server.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heban.pestai.server.admin.request.AdminLoginRequest;
import com.heban.pestai.server.admin.request.AdminPasswordRequest;
import com.heban.pestai.server.admin.vo.AdminLoginVO;
import com.heban.pestai.server.common.ApiResponse;

/**
 * 后台登录（/admin/api/auth/**，不在 /pestai/v1 下，使用会话 Cookie 而不是 App 的 JWT）。
 * login 不经过 AdminInterceptor；其余接口需要登录，除 GET 外还要带请求头 X-CSRF-Token。
 */
@RestController
@RequestMapping("/admin/api/auth")
public class AdminAuthController {

    private final AdminAuthService authService;

    public AdminAuthController(AdminAuthService authService) {
        this.authService = authService;
    }

    /** POST auth/login：{username, password} → {username, must_change_password, csrf_token}；40102 / 42901 */
    @PostMapping("login")
    public ApiResponse<AdminLoginVO> login(@Valid @RequestBody AdminLoginRequest request, HttpServletRequest http) {
        return ApiResponse.success(authService.login(request.getUsername(), request.getPassword(), http));
    }

    /** POST auth/logout */
    @PostMapping("logout")
    public ApiResponse<Void> logout(HttpServletRequest http) {
        authService.logout(http);
        return ApiResponse.success();
    }

    /** GET auth/me：未登录 40101 */
    @GetMapping("me")
    public ApiResponse<AdminLoginVO> me(HttpServletRequest http) {
        return ApiResponse.success(authService.me(http));
    }

    /** POST auth/password：{old_password, new_password}；成功后 must_change_password = false */
    @PostMapping("password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody AdminPasswordRequest request) {
        authService.changePassword(request.getOldPassword(), request.getNewPassword());
        return ApiResponse.success();
    }
}
