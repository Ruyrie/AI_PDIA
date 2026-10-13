package com.heban.pestai.server.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.heban.pestai.server.common.ApiResponse;
import com.heban.pestai.server.common.Mode;
import com.heban.pestai.server.request.RecordPageRequest;
import com.heban.pestai.server.security.UserContext;
import com.heban.pestai.server.service.RecognitionService;
import com.heban.pestai.server.vo.PageVO;
import com.heban.pestai.server.vo.RecognitionVO;
import com.heban.pestai.server.vo.RecordItemVO;

/**
 * 识别：上传识别、详情、重新识别、最近识别、分页、删除（需要登录；只能访问自己的记录）。
 * recognitions/recent 是固定字面量，匹配时优先于 recognitions/{id}；{id} 限定为数字。
 */
@Validated
@RestController
@RequestMapping("recognitions")
public class RecognitionController {

    private final RecognitionService recognitionService;

    public RecognitionController(RecognitionService recognitionService) {
        this.recognitionService = recognitionService;
    }

    /** POST recognitions（multipart：image、mode） */
    @PostMapping
    public ApiResponse<RecognitionVO> recognize(@RequestPart("image") MultipartFile image,
                                                @RequestParam("mode") @NotBlank @Pattern(regexp = Mode.REGEX) String mode) {
        long userId = UserContext.requireUserId();
        return ApiResponse.success(recognitionService.recognize(userId, image, mode));
    }

    /** GET recognitions/recent?size=3（1–10） */
    @GetMapping("recent")
    public ApiResponse<List<RecordItemVO>> recent(@RequestParam(value = "size", defaultValue = "3") @Min(1) @Max(10) int size) {
        return ApiResponse.success(recognitionService.recent(UserContext.requireUserId(), size));
    }

    /** GET recognitions?mode=&page=1&size=20 */
    @GetMapping
    public ApiResponse<PageVO<RecordItemVO>> list(@Valid RecordPageRequest request) {
        return ApiResponse.success(recognitionService.page(UserContext.requireUserId(), request));
    }

    /** GET recognitions/{id} */
    @GetMapping("{id:\\d+}")
    public ApiResponse<RecognitionVO> detail(@PathVariable("id") @Positive Long id) {
        return ApiResponse.success(recognitionService.detail(UserContext.requireUserId(), id));
    }

    /** POST recognitions/{id}/retry：用原照片重新识别 */
    @PostMapping("{id:\\d+}/retry")
    public ApiResponse<RecognitionVO> retry(@PathVariable("id") @Positive Long id) {
        return ApiResponse.success(recognitionService.retry(UserContext.requireUserId(), id));
    }

    /** DELETE recognitions/{id}：逻辑删除 */
    @DeleteMapping("{id:\\d+}")
    public ApiResponse<Void> delete(@PathVariable("id") @Positive Long id) {
        recognitionService.delete(UserContext.requireUserId(), id);
        return ApiResponse.success();
    }
}
