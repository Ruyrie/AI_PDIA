package com.heban.pestai.server.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.heban.pestai.server.admin.vo.AdminLogVO;
import com.heban.pestai.server.common.ApiResponse;
import com.heban.pestai.server.vo.PageVO;

/** 后台操作日志（需要登录），按时间倒序 */
@Validated
@RestController
@RequestMapping("/admin/api/logs")
public class AdminLogController {

    private final AdminLogService logService;

    public AdminLogController(AdminLogService logService) {
        this.logService = logService;
    }

    /** GET logs?action=&page=1&size=20 */
    @GetMapping
    public ApiResponse<PageVO<AdminLogVO>> logs(
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(logService.page(action, page, size));
    }
}
