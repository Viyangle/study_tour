package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.RegionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/regions")
public class RegionController {

    @Autowired
    private RegionService regionService;

    @GetMapping("/provinces")
    public Result getProvinces() {
        log.info("查询省级行政区");
        return Result.success(regionService.getProvinces());
    }

    @GetMapping("/children")
    public Result getChildren(@RequestParam String parentAdcode) {
        log.info("查询子级行政区, parentAdcode={}", parentAdcode);
        return Result.success(regionService.getChildren(parentAdcode));
    }
}
