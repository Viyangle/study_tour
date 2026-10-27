package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.utils.AliOSSUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
public class UploadController {
    @Autowired
    private AliOSSUtils aliOSSUtils;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result upload(@RequestPart(value = "image", required = false) MultipartFile image) throws Exception {
        if (image == null || image.isEmpty()) {
            return Result.error("请选择要上传的图片");
        }
        log.info("文件上传，文件名:{}", image.getOriginalFilename());

        String url = aliOSSUtils.upload(image);
        log.info("文件上传成功，文件url:{}", url);

        return Result.success(url);
    }
}
