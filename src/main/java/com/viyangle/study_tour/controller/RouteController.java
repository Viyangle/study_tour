package com.viyangle.study_tour.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.Route;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.service.AiRoutePlanningService;
import com.viyangle.study_tour.service.RouteService;
import com.viyangle.study_tour.service.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/routes")
public class RouteController {
    private static final Set<String> ALLOWED_TAGS = Set.of(
            "历史人文", "博物馆研学", "非遗体验", "科技探索", "自然生态",
            "地理地质", "航天航空", "农耕劳动", "艺术美育", "红色教育",
            "高校参访", "职业启蒙", "英语实践", "摄影记录", "亲子互动"
    );

    @Autowired
    private RouteService routeService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AiRoutePlanningService aiRoutePlanningService;

    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping
    public Result getAllRoutes(@RequestParam(defaultValue = "1") Integer pageNum,
                               @RequestParam(defaultValue = "10") Integer pageSize,
                               @RequestParam Long accountId) {
        log.info("分页获取路线, accountId={}, pageNum={}, pageSize={}", accountId, pageNum, pageSize);
        List<Route> routes = routeService.getPagedRoutesByPreference(accountId, pageNum, pageSize);
        return Result.success(routes);
    }

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

    @PostMapping("/{id}/publish")
    @RequireRole({"USER", "LEADER"})
    public Result publishRoute(@PathVariable Long id, @RequestBody Project project) {
        project.setRouteId(id);
        project.setLeaderAccountId(null);
        project.setStatus("OPEN");
        log.info("Publish route as project, routeId={}, representedCount={}", id, project.getRepresentedCount());
        return Result.success(projectService.createProject(project));
    }

    @PostMapping("/optimize")
    public Result optimizeSubmittedRoute(@RequestBody List<RouteAttraction> routeAttractions,
                                         @RequestParam(required = false) String message) throws Exception {
        long startMs = System.currentTimeMillis();
        log.info("Optimize submitted route start, attractionCount={}",
                routeAttractions == null ? 0 : routeAttractions.size());
        AIRoutePlan optimized = aiRoutePlanningService.optimizeSubmittedRoute(routeAttractions, message);
        Long routeId = routeService.saveOptimizedRoute(optimized.getTag(), toRouteAttractions(optimized.getItems()));
        log.info("Optimize submitted route done, routeId={}, costMs={}",
                routeId, System.currentTimeMillis() - startMs);
        return Result.success(routeId);
    }

    @PostMapping("/ai/{memoryId}")
    public Result generateRouteByAIV2(@PathVariable String memoryId, @RequestParam String message) throws Exception {
        long startMs = System.currentTimeMillis();
        log.info("Generate route by AI v2 start, memoryId={}", memoryId);
        AIRoutePlan aiRoutePlan = aiRoutePlanningService.planRouteV2(memoryId, message);
        Result result = Result.success(routeService.saveOrUpdateAIConversationRoute(memoryId, aiRoutePlan.getTag(), toRouteAttractions(aiRoutePlan.getItems())));
        long costMs = System.currentTimeMillis() - startMs;
        log.info("Generate route by AI v2 done, memoryId={}, costMs={}", memoryId, costMs);
        return result;
    }

    public AIRoutePlan parseAiResult(String aiText) throws Exception {
        String json = aiText.replaceAll("(?s)^```json\\s*|\\s*```$", "").trim();
        AIRoutePlan plan = objectMapper.readValue(json, AIRoutePlan.class);
        validateAiPlan(plan);
        return plan;
    }

    private List<RouteAttraction> toRouteAttractions(List<AIRouteItem> items) {
        return items.stream().map(i -> {
            RouteAttraction ra = new RouteAttraction();
            ra.setPoiId(i.getPoiId());
            ra.setVisitOrder(i.getVisitOrder());
            if (i.getVisitTime() != null && !i.getVisitTime().isBlank()) {
                ra.setVisitTime(LocalDateTime.parse(i.getVisitTime()));
            }
            ra.setRecommendedDuration(i.getRecommendedDuration());
            ra.setNotes(extractCommuteNotes(i.getNotes()));
            return ra;
        }).toList();
    }

    private String extractCommuteNotes(String raw) {
        if (raw == null || raw.isBlank()) {
            return "公交/地铁前往下一个景点";
        }
        List<String> parts = Arrays.stream(raw.split("[。.!！?？；;]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        for (String part : parts) {
            String lower = part.toLowerCase();
            if (part.contains("乘坐")
                    || part.contains("换乘")
                    || part.contains("公交")
                    || part.contains("地铁")
                    || part.contains("步行")
                    || part.contains("打车")
                    || part.contains("前往")
                    || lower.contains("commute")
                    || lower.contains("transit")) {
                return part;
            }
        }
        return parts.get(0);
    }

    private void validateAiPlan(AIRoutePlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("AI route plan is empty");
        }
        if (plan.getTag() == null || plan.getTag().isBlank()) {
            throw new IllegalArgumentException("AI route plan tag is empty");
        }
        if (!ALLOWED_TAGS.contains(plan.getTag())) {
            throw new IllegalArgumentException("AI route plan tag is invalid: " + plan.getTag());
        }
        validateAiItems(plan.getItems());
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
