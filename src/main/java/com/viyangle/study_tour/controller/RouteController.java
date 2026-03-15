package com.viyangle.study_tour.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.aiservice.ConsultantService;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.pojo.Route;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.service.RouteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/routes")
public class RouteController {

    @Autowired
    private RouteService routeService;

    @Autowired
    private ConsultantService consultantService;

    @Autowired
    private ObjectMapper objectMapper;
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

    @PostMapping("/ai/{memoryId}")
    public Result generateRouteByAI(@PathVariable String memoryId, String message) throws Exception {
        log.info("AI生成路线");
        String aiText = consultantService.chat(memoryId, message);
        log.info("AI返回结果: {}", aiText);
        List<RouteAttraction> routeAttractions = parseAiResult(aiText);
        return Result.success(routeService.generateRouteByManual(routeAttractions));
    }

    public List<RouteAttraction> parseAiResult(String aiText) throws Exception {
        // 若 AI 可能返回 ```json ...```，先清洗
        String json = aiText.replaceAll("(?s)^```json\\s*|\\s*```$", "").trim();

        List<AIRouteItem> items = objectMapper.readValue(
                json, new TypeReference<List<AIRouteItem>>() {}
        );

        return items.stream().map(i -> {
            RouteAttraction ra = new RouteAttraction();
            ra.setAttractionId(i.getAttractionId());
            ra.setVisitOrder(i.getVisitOrder());
            ra.setVisitTime(LocalDateTime.parse(i.getVisitTime()));
            ra.setRecommendedDuration(i.getRecommendedDuration());
            ra.setNotes(i.getNotes());
            return ra;
        }).toList();
    }
}
