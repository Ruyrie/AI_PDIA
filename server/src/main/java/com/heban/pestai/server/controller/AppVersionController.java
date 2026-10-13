package com.heban.pestai.server.controller;

import jakarta.validation.constraints.Min;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.heban.pestai.server.common.ApiResponse;
import com.heban.pestai.server.service.AppVersionService;
import com.heban.pestai.server.vo.AppVersionVO;

/** 查询最新版本（预留，v1.0 App 不调用；白名单接口，未登录也能检查更新） */
@Validated
@RestController
@RequestMapping("app")
public class AppVersionController {

    private final AppVersionService appVersionService;

    public AppVersionController(AppVersionService appVersionService) {
        this.appVersionService = appVersionService;
    }

    /** GET app/version?version_code=1（可选） */
    @GetMapping("version")
    public ApiResponse<AppVersionVO> latest(@RequestParam(value = "version_code", required = false) @Min(1) Integer versionCode) {
        return ApiResponse.success(appVersionService.getLatest(versionCode));
    }
}
