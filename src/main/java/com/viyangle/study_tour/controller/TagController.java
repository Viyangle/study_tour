package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.TagVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标签维度元数据：前端据此渲染 15 维雷达图/标签面板，避免硬编码维度顺序。
 */
@Slf4j
@RestController
@RequestMapping("/tags")
public class TagController {

    @Autowired
    private TagVectorService tagVectorService;

    @GetMapping("/interests")
    public Result listInterestDimensions() {
        log.info("获取标签向量维度, dimVersion={}, ready={}",
                tagVectorService.dimVersion(), tagVectorService.isReady());
        return Result.success(tagVectorService.dimensions());
    }
}
