package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.pojo.Route;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.service.RouteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/routes")
public class RouteController {

    @Autowired
    private RouteService routeService;
    @GetMapping("/{id}")
    public Result getRouteById(@PathVariable Long id) {
        log.info("获取路线: {}", id);
        return Result.success(routeService.getRouteById(id));
    }

    @PostMapping("/manual")
    public Result generateRouteByManual(@RequestBody List<RouteAttraction> routeAttractions) {
        log.info("手动生成路线");
        return Result.success(routeService.generateRouteByManual(routeAttractions));
    }

    @PostMapping("/ai")
    public Result generateRouteByAI() {
        //TODO: generate route by AI
        return null;
    }
}
