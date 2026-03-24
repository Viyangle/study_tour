package com.viyangle.study_tour.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.aiservice.ConsultantService;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.service.AiRoutePlanningService;
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
    private AiRoutePlanningService aiRoutePlanningService;

    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping("/{id}")
    public Result getRouteById(@PathVariable Long id) {
        log.info("Get route: {}", id);
        return Result.success(routeService.getRouteById(id));
    }

    @PostMapping("/manual")
    public Result generateRouteByManual(@RequestBody List<RouteAttraction> routeAttractions) {
        log.info("Generate manual route");
        return Result.success(routeService.generateRouteByManual(routeAttractions));
    }

    @PostMapping("/ai/{memoryId}")
    public Result generateRouteByAI(@PathVariable String memoryId, @RequestParam String message) throws Exception {
        log.info("Generate route by AI v1");
        String aiText = consultantService.chat(memoryId, message);
        log.info("AI raw response: {}", aiText);
        List<RouteAttraction> routeAttractions = parseAiResult(aiText);
        return Result.success(routeService.generateRouteByManual(routeAttractions));
    }

    @PostMapping("/ai/v2/{memoryId}")
    public Result generateRouteByAIV2(@PathVariable String memoryId, @RequestParam String message) throws Exception {
        log.info("Generate route by AI v2");
        List<RouteAttraction> routeAttractions = aiRoutePlanningService.planRouteV2(memoryId, message);
        return Result.success(routeService.generateRouteByManual(routeAttractions));
    }

    public List<RouteAttraction> parseAiResult(String aiText) throws Exception {
        String json = aiText.replaceAll("(?s)^```json\\s*|\\s*```$", "").trim();

        List<AIRouteItem> items = objectMapper.readValue(json, new TypeReference<List<AIRouteItem>>() {});
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
            throw new IllegalArgumentException("AI route is empty");
        }

        Set<Integer> orders = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            AIRouteItem item = items.get(i);
            int row = i + 1;

            if (item.getVisitOrder() == null || item.getVisitOrder() <= 0) {
                throw new IllegalArgumentException("Row " + row + ": invalid visitOrder");
            }
            if (!orders.add(item.getVisitOrder())) {
                throw new IllegalArgumentException("Duplicated visitOrder: " + item.getVisitOrder());
            }

            if (item.getPoiId() == null || item.getPoiId().isBlank()) {
                throw new IllegalArgumentException("Row " + row + ": invalid poiId");
            }

            if (item.getRecommendedDuration() == null || item.getRecommendedDuration() <= 0) {
                throw new IllegalArgumentException("Row " + row + ": invalid recommendedDuration");
            }

            if (item.getVisitTime() != null && !item.getVisitTime().isBlank()) {
                try {
                    LocalDateTime.parse(item.getVisitTime());
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Row " + row + ": invalid visitTime format yyyy-MM-dd'T'HH:mm:ss");
                }
            }
        }
    }
}

