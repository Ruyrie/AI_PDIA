package com.heban.pestai.server.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.heban.pestai.server.common.ApiResponse;
import com.heban.pestai.server.security.UserContext;
import com.heban.pestai.server.service.FileStorageService;
import com.heban.pestai.server.vo.UploadVO;

/** 上传聊天图片（需要登录） */
@RestController
@RequestMapping("files")
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /** POST files/image（multipart，字段 image）：保存到 chat 目录，不写数据库 */
    @PostMapping("image")
    public ApiResponse<UploadVO> uploadImage(@RequestPart("image") MultipartFile image) {
        UserContext.requireUserId();
        String relative = fileStorageService.save(image, FileStorageService.CHAT);
        return ApiResponse.success(new UploadVO(fileStorageService.toUrl(relative)));
    }
}
