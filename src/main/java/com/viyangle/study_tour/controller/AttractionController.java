package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AttractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/attractions")
public class AttractionController {

    @Autowired
    private AttractionService attractionService;

    @GetMapping
    public Result getAllAttractions(@RequestParam(required = false) String regionCode,
                                    @RequestParam(defaultValue = "1") Integer pageNum,
                                    @RequestParam(defaultValue = "10") Integer pageSize) {
        log.info("按地区分页获取景点, regionCode={}, pageNum={}, pageSize={}", regionCode, pageNum, pageSize);
        return Result.success(attractionService.getPagedAttractionsByRegion(regionCode, pageNum, pageSize));
    }
}
