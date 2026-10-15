package com.heban.pestai.server.admin;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.heban.pestai.server.admin.request.AdminProfileRequest;
import com.heban.pestai.server.admin.request.DeactivateUserRequest;
import com.heban.pestai.server.admin.request.DisableUserRequest;
import com.heban.pestai.server.admin.vo.AdminMessageVO;
import com.heban.pestai.server.admin.vo.AdminRecognitionVO;
import com.heban.pestai.server.admin.vo.AdminSessionVO;
import com.heban.pestai.server.admin.vo.AdminUserDetailVO;
import com.heban.pestai.server.admin.vo.AdminUserRowVO;
import com.heban.pestai.server.admin.vo.TempPasswordVO;
import com.heban.pestai.server.common.ApiResponse;
import com.heban.pestai.server.vo.PageVO;

/** 后台用户管理（需要登录）：分页 page ≥ 1、size 1–100；路径中的 id 限定为数字 */
@Validated
@RestController
@RequestMapping("/admin/api")
public class AdminUserController {

    private final AdminUserService userService;

    public AdminUserController(AdminUserService userService) {
        this.userService = userService;
    }

    /** GET users?keyword=&status=NORMAL|DISABLED&page=1&size=20 */
    @GetMapping("users")
    public ApiResponse<PageVO<AdminUserRowVO>> users(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(userService.page(keyword, status, page, size));
    }

    @GetMapping("users/{id:\\d+}")
    public ApiResponse<AdminUserDetailVO> detail(@PathVariable("id") @Positive long id) {
        return ApiResponse.success(userService.detail(id));
    }

    /** 识别记录（含用户已删除的，deleted = true） */
    @GetMapping("users/{id:\\d+}/recognitions")
    public ApiResponse<PageVO<AdminRecognitionVO>> recognitions(
            @PathVariable("id") @Positive long id,
            @RequestParam(value = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(userService.recognitions(id, page, size));
    }

    @GetMapping("users/{id:\\d+}/sessions")
    public ApiResponse<PageVO<AdminSessionVO>> sessions(
            @PathVariable("id") @Positive long id,
            @RequestParam(value = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(userService.sessions(id, page, size));
    }

    @GetMapping("sessions/{id:\\d+}/messages")
    public ApiResponse<List<AdminMessageVO>> messages(@PathVariable("id") @Positive long id) {
        return ApiResponse.success(userService.messages(id));
    }

    /** POST users/{id}/disable：{reason}（可空，请求体可省略）；同时强制下线 */
    @PostMapping("users/{id:\\d+}/disable")
    public ApiResponse<Void> disable(@PathVariable("id") @Positive long id,
                                     @Valid @RequestBody(required = false) DisableUserRequest request) {
        userService.disable(id, request == null ? null : request.getReason());
        return ApiResponse.success();
    }

    @PostMapping("users/{id:\\d+}/enable")
    public ApiResponse<Void> enable(@PathVariable("id") @Positive long id) {
        userService.enable(id);
        return ApiResponse.success();
    }

    /** POST users/{id}/logout：token_version + 1 */
    @PostMapping("users/{id:\\d+}/logout")
    public ApiResponse<Void> logout(@PathVariable("id") @Positive long id) {
        userService.forceLogout(id);
        return ApiResponse.success();
    }

    @PutMapping("users/{id:\\d+}/profile")
    public ApiResponse<AdminUserDetailVO> updateProfile(@PathVariable("id") @Positive long id,
                                                        @Valid @RequestBody AdminProfileRequest request) {
        return ApiResponse.success(userService.updateProfile(id, request));
    }

    /** POST users/{id}/reset-password：{temp_password}（只返回这一次） */
    @PostMapping("users/{id:\\d+}/reset-password")
    public ApiResponse<TempPasswordVO> resetPassword(@PathVariable("id") @Positive long id) {
        return ApiResponse.success(userService.resetPassword(id));
    }

    /** DELETE recognitions/{id}：彻底删除记录与照片（不存在直接成功） */
    @DeleteMapping("recognitions/{id:\\d+}")
    public ApiResponse<Void> deleteRecognition(@PathVariable("id") @Positive long id) {
        userService.deleteRecognition(id);
        return ApiResponse.success();
    }

    /** DELETE sessions/{id}：删除会话、消息与聊天图片（不存在直接成功） */
    @DeleteMapping("sessions/{id:\\d+}")
    public ApiResponse<Void> deleteSession(@PathVariable("id") @Positive long id) {
        userService.deleteSession(id);
        return ApiResponse.success();
    }

    /** POST users/{id}/deactivate：{confirm_phone}（必须等于完整手机号）；注销并删除全部数据，不发短信 */
    @PostMapping("users/{id:\\d+}/deactivate")
    public ApiResponse<Void> deactivate(@PathVariable("id") @Positive long id,
                                        @Valid @RequestBody DeactivateUserRequest request) {
        userService.deactivate(id, request.getConfirmPhone());
        return ApiResponse.success();
    }
}
