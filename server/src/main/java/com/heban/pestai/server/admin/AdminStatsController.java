package com.heban.pestai.server.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.heban.pestai.server.admin.vo.StatsDistributionVO;
import com.heban.pestai.server.admin.vo.StatsOverviewVO;
import com.heban.pestai.server.admin.vo.StatsTrendVO;
import com.heban.pestai.server.common.ApiResponse;

/** 后台数据统计（需要登录） */
@RestController
@RequestMapping("/admin/api/stats")
public class AdminStatsController {

    private final AdminStatsService statsService;

    public AdminStatsController(AdminStatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("overview")
    public ApiResponse<StatsOverviewVO> overview() {
        return ApiResponse.success(statsService.overview());
    }

    /** GET stats/trend?days=7|30（默认 7；其他值 40001） */
    @GetMapping("trend")
    public ApiResponse<StatsTrendVO> trend(@RequestParam(value = "days", defaultValue = "7") int days) {
        return ApiResponse.success(statsService.trend(days));
    }

    @GetMapping("distribution")
    public ApiResponse<StatsDistributionVO> distribution() {
        return ApiResponse.success(statsService.distribution());
    }
}
