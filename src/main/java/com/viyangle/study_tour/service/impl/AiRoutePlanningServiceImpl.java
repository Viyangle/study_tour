package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.aiservice.CandidateSelectorService;
import com.viyangle.study_tour.aiservice.RouteComposerService;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.service.AiRoutePlanningService;
import com.viyangle.study_tour.utils.AmapTransitClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AiRoutePlanningServiceImpl implements AiRoutePlanningService {

    @Autowired
    private CandidateSelectorService candidateSelectorService;

    @Autowired
    private RouteComposerService routeComposerService;

    @Autowired
    private AttractionMapper attractionMapper;

    @Autowired
    private AmapTransitClient amapTransitClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public List<RouteAttraction> planRouteV2(String memoryId, String message) throws Exception {
        String candidatePrompt = buildCandidatePrompt(message);
        String candidateText = candidateSelectorService.chat(memoryId, candidatePrompt);
        List<AIRouteItem> candidateItems = parseAiItems(candidateText);
        List<Attraction> candidates = loadCandidateAttractions(candidateItems);

        if (candidates.size() < 2) {
            throw new IllegalArgumentException("Candidate POIs are not enough for routing.");
        }

        List<AmapTransitClient.TransitEdge> matrix = amapTransitClient.buildUndirectedMatrix(candidates);

        String finalPrompt = buildFinalPrompt(message, candidates, matrix);
        String finalText = routeComposerService.chat(memoryId, finalPrompt);
        List<AIRouteItem> finalItems = parseAiItems(finalText);
        validateFinalItems(finalItems, candidates);

        return toRouteAttractions(finalItems);
    }

    private List<AIRouteItem> parseAiItems(String aiText) throws Exception {
        String json = aiText.replaceAll("(?s)^```json\\s*|\\s*```$", "").trim();
        List<AIRouteItem> items = objectMapper.readValue(json, new TypeReference<List<AIRouteItem>>() {});
        validateAiItems(items);
        return items;
    }

    private void validateAiItems(List<AIRouteItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("AI route is empty");
        }

        Set<Integer> orders = new LinkedHashSet<>();
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
                    throw new IllegalArgumentException("Row " + row + ": invalid visitTime format");
                }
            }
        }
    }

    private List<Attraction> loadCandidateAttractions(List<AIRouteItem> candidateItems) {
        Map<String, Integer> orderMap = new LinkedHashMap<>();
        for (AIRouteItem item : candidateItems) {
            if (!orderMap.containsKey(item.getPoiId())) {
                orderMap.put(item.getPoiId(), item.getVisitOrder());
            }
        }

        List<Attraction> list = new ArrayList<>();
        for (String poiId : orderMap.keySet()) {
            Attraction attraction = attractionMapper.selectByPoiId(poiId);
            if (attraction != null && attraction.getLocation() != null && !attraction.getLocation().isBlank()) {
                list.add(attraction);
            }
        }

        list.sort(Comparator.comparingInt(a -> orderMap.getOrDefault(a.getPoiId(), Integer.MAX_VALUE)));

        if (list.size() > 15) {
            list = new ArrayList<>(list.subList(0, 15));
        }
//        else if (list.size() < 10) {
//            List<Attraction> fallback = attractionMapper.selectAll();
//            for (Attraction a : fallback) {
//                if (list.size() >= 10) {
//                    break;
//                }
//                if (a.getPoiId() == null || a.getPoiId().isBlank() || a.getLocation() == null || a.getLocation().isBlank()) {
//                    continue;
//                }
//                boolean exists = list.stream().anyMatch(x -> x.getPoiId().equals(a.getPoiId()));
//                if (!exists) {
//                    list.add(a);
//                }
//            }
//        }
        return list;
    }

    private void validateFinalItems(List<AIRouteItem> finalItems, List<Attraction> candidates) {
        Set<String> allowedPoiIds = new LinkedHashSet<>();
        for (Attraction a : candidates) {
            allowedPoiIds.add(a.getPoiId());
        }
        for (AIRouteItem item : finalItems) {
            if (!allowedPoiIds.contains(item.getPoiId())) {
                throw new IllegalArgumentException("Final route contains poiId outside candidate set: " + item.getPoiId());
            }
        }
    }

    private List<RouteAttraction> toRouteAttractions(List<AIRouteItem> items) {
        List<RouteAttraction> result = new ArrayList<>();
        for (AIRouteItem i : items) {
            RouteAttraction ra = new RouteAttraction();
            ra.setPoiId(i.getPoiId());
            ra.setVisitOrder(i.getVisitOrder());
            if (i.getVisitTime() != null && !i.getVisitTime().isBlank()) {
                ra.setVisitTime(LocalDateTime.parse(i.getVisitTime()));
            }
            ra.setRecommendedDuration(i.getRecommendedDuration());
            ra.setNotes(i.getNotes());
            result.add(ra);
        }
        return result;
    }

    private String buildCandidatePrompt(String userMessage) {
        return """
                你现在处于路线规划第一阶段：只做候选景点筛选。
                根据用户需求，从现有知识中筛选 10-15 个候选景点。
                输出必须是 JSON 数组，不要 Markdown，不要解释。
                每个元素字段必须包含: poiId, visitOrder, visitTime, recommendedDuration, notes。
                规则:
                1) visitOrder 从 1 开始递增且不重复。
                2) visitTime 可为 null。
                3) recommendedDuration 必须 > 0。
                4) notes 简短说明推荐理由即可。
                用户需求:
                """ + userMessage;
    }

    private String buildFinalPrompt(String userMessage, List<Attraction> candidates, List<AmapTransitClient.TransitEdge> matrix) {
        StringBuilder sb = new StringBuilder();
        sb.append("你现在处于路线规划第二阶段：基于候选景点与通勤矩阵输出最终路线。\n");
        sb.append("必须只使用候选 poiId，输出 JSON 数组，不要 Markdown，不要解释。\n");
        sb.append("字段必须包含: poiId, visitOrder, visitTime, recommendedDuration, notes。\n");
        sb.append("notes 里要写联系电话，开放时间等约束，也要体现相邻景点通勤方式/大概步行距离/线路信息。\n");
        sb.append("用户需求:\n").append(userMessage).append("\n\n");

        sb.append("候选景点列表:\n");
        for (int i = 0; i < candidates.size(); i++) {
            Attraction a = candidates.get(i);
            sb.append(i + 1)
                    .append(". poiId=").append(a.getPoiId())
                    .append(", name=").append(nonNull(a.getName()))
                    .append(", citycode=").append(nonNull(a.getCitycode()))
                    .append(", type=").append(nonNull(a.getType()))
                    .append("\n");
        }

        sb.append("\n候选景点两两通勤矩阵(无向):\n");
        for (AmapTransitClient.TransitEdge e : matrix) {
            sb.append(e.getFromPoiId()).append(" <-> ").append(e.getToPoiId())
                    .append(" | routeDistanceM=").append(e.getRouteDistanceM())
                    .append(", bestTransitDistanceM=").append(e.getBestTransitDistanceM())
                    .append(", bestWalkingDistanceM=").append(e.getBestWalkingDistanceM())
                    .append(", lines=").append(e.getBestLines() == null ? "[]" : e.getBestLines())
                    .append("\n");
        }
        return sb.toString();
    }

    private String nonNull(String s) {
        return s == null ? "" : s;
    }
}
