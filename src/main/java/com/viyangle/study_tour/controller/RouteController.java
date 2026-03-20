package com.viyangle.study_tour.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.aiservice.ConsultantService;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.service.RouteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        String json = aiText.replaceAll("(?s)^```json\\s*|\\s*```$", "").trim();

        List<AIRouteItem> items = objectMapper.readValue(
                json, new TypeReference<List<AIRouteItem>>() {}
        );
        validateAiItems(items);

        return items.stream().map(i -> {
            RouteAttraction ra = new RouteAttraction();
            ra.setPoiId(i.getPoiId());
            ra.setVisitOrder(i.getVisitOrder());
            if (i.getVisitTime() != null && !i.getVisitTime().isBlank()) {
                ra.setVisitTime(LocalDateTime.parse(i.getVisitTime()));
            }
            ra.setRecommendedDuration(i.getRecommendedDuration());
            ra.setNotes(i.getNotes());
            return ra;
        }).toList();
    }

    private void validateAiItems(List<AIRouteItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("AI生成路线为空");
        }

        Set<Integer> orders = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            AIRouteItem item = items.get(i);
            int row = i + 1;

            if (item.getVisitOrder() == null || item.getVisitOrder() <= 0) {
                throw new IllegalArgumentException("第" + row + "项 visitOrder 非法");
            }
            if (!orders.add(item.getVisitOrder())) {
                throw new IllegalArgumentException("visitOrder 重复: " + item.getVisitOrder());
            }

            if (item.getPoiId() == null || item.getPoiId().isBlank()) {
                throw new IllegalArgumentException("第" + row + "项 poiId 非法");
            }

            if (item.getRecommendedDuration() == null || item.getRecommendedDuration() <= 0) {
                throw new IllegalArgumentException("第" + row + "项 recommendedDuration 非法");
            }

            if (item.getVisitTime() != null && !item.getVisitTime().isBlank()) {
                try {
                    LocalDateTime.parse(item.getVisitTime());
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("第" + row + "项 visitTime 格式非法，应为 yyyy-MM-dd'T'HH:mm:ss");
                }
            }
        }
    }
}
