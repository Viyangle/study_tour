package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.RouteTagVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多标签路线：返回路线 15 维向量。
 */
@Slf4j
@RestController
@RequestMapping("/routes")
public class RouteTagController {

    @Autowired
    private RouteTagVectorService routeTagVectorService;

    @GetMapping("/{id}/tags")
    public Result getRouteTags(@PathVariable Long id) {
        log.info("获取路线标签向量: routeId={}", id);
        return Result.success(routeTagVectorService.view(id));
    }
}
