package com.heban.pestai.server.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.heban.pestai.server.common.ApiResponse;
import com.heban.pestai.server.security.UserContext;
import com.heban.pestai.server.service.AsrService;
import com.heban.pestai.server.vo.AsrVO;

/** App 语音输入：语音识别（需要登录）。 */
@RestController
@RequestMapping("ai")
public class AsrController {

    private final AsrService asrService;

    public AsrController(AsrService asrService) {
        this.asrService = asrService;
    }

    /**
     * POST ai/asr（multipart，字段 audio）：WAV（PCM 16 位、16kHz、单声道），≤ 60 秒、≤ 2MB；返回 {text}。
     * 没听清 40001、每人每分钟超过 20 次 42901、识别服务不可用 50201。音频不保存。
     */
    @PostMapping("asr")
    public ApiResponse<AsrVO> recognize(@RequestPart("audio") MultipartFile audio) {
        return ApiResponse.success(asrService.recognize(UserContext.requireUserId(), audio));
    }
}
