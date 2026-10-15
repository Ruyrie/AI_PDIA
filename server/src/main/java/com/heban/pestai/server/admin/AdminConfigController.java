package com.heban.pestai.server.admin;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heban.pestai.server.admin.request.ConfigResetRequest;
import com.heban.pestai.server.admin.request.ConfigTestRequest;
import com.heban.pestai.server.admin.request.ConfigUpdateRequest;
import com.heban.pestai.server.admin.vo.ConfigItemVO;
import com.heban.pestai.server.admin.vo.ConfigTestVO;
import com.heban.pestai.server.common.ApiResponse;

/** 后台系统配置：API Key、模型名、短信签名与模板（需要登录；修改后立即生效） */
@RestController
@RequestMapping("/admin/api/config")
public class AdminConfigController {

    private final AdminConfigService configService;

    public AdminConfigController(AdminConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ApiResponse<List<ConfigItemVO>> list() {
        return ApiResponse.success(configService.list());
    }

    /** PUT config/{key}：{value, admin_password}（密钥类必须带 admin_password） */
    @PutMapping("{key}")
    public ApiResponse<ConfigItemVO> update(@PathVariable("key") String key,
                                            @Valid @RequestBody ConfigUpdateRequest request) {
        return ApiResponse.success(configService.update(key, request.getValue(), request.getAdminPassword()));
    }

    /** DELETE config/{key}：{admin_password}（密钥类必须带；非密钥类请求体可省略）；恢复为 .env / 默认值 */
    @DeleteMapping("{key}")
    public ApiResponse<ConfigItemVO> reset(@PathVariable("key") String key,
                                           @Valid @RequestBody(required = false) ConfigResetRequest request) {
        return ApiResponse.success(configService.reset(key, request == null ? null : request.getAdminPassword()));
    }

    /** POST config/test/{group}：vision / chat / asr / sms（sms 时 {phone}） */
    @PostMapping("test/{group}")
    public ApiResponse<ConfigTestVO> test(@PathVariable("group") String group,
                                          @RequestBody(required = false) ConfigTestRequest request) {
        return ApiResponse.success(configService.test(group, request == null ? null : request.getPhone()));
    }
}
