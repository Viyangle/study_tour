package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AttractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/attractions")
public class AttractionController {

    @Autowired
    private AttractionService attractionService;
    @GetMapping
    public Result getAllAttractions() {
        log.info("获取所有景点信息");
        return Result.success(attractionService.getAllAttractions());
    }

    //TODO: 分页（不能每次都直接给所有景点信息）
}
