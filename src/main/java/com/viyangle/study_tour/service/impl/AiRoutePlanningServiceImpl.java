package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.aiservice.RouteComposerService;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.VectorRetrievalResult;
import com.viyangle.study_tour.service.AiRoutePlanningService;
import com.viyangle.study_tour.service.VectorCandidateRetrieverService;
import com.viyangle.study_tour.utils.AmapTransitClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AiRoutePlanningServiceImpl implements AiRoutePlanningService {
    private static final Set<String> ALLOWED_TAGS = new HashSet<>(List.of(
            "历史人文", "博物馆研学", "非遗体验", "科技探索", "自然生态",
            "地理地质", "航天航空", "农耕劳动", "艺术美育", "红色教育",
            "高校参访", "职业启蒙", "英语实践", "摄影记录", "亲子互动"
    ));

    @Autowired
    private RouteComposerService routeComposerService;

    @Autowired
    private AttractionMapper attractionMapper;

    @Autowired
    private AmapTransitClient amapTransitClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VectorCandidateRetrieverService vectorCandidateRetrieverService;

    @Override
    public AIRoutePlan planRouteV2(String memoryId, String message) throws Exception {
        VectorRetrievalResult retrievalResult = vectorCandidateRetrieverService.retrieveCandidatesWithTexts(message, 15, 0);
        List<String> candidatePoiIds = retrievalResult.getPoiIds();
        List<Attraction> candidates = loadCandidateAttractions(candidatePoiIds);
        if (candidates.size() < 2) {
            candidates = loadFallbackAttractions(15);
        }

        if (candidates.size() < 2) {
            throw new IllegalArgumentException("Candidate POIs are not enough for routing.");
        }

        List<AmapTransitClient.TransitEdge> matrix = amapTransitClient.buildUndirectedMatrix(candidates);

        String finalPrompt = buildFinalPrompt(message, candidates, matrix, retrievalResult.getRetrievedTexts());
        String finalText = routeComposerService.chat(memoryId, finalPrompt);
        AIRoutePlan plan = parseAiPlan(finalText);
        validateFinalItems(plan.getItems(), candidates);

        return plan;
    }

    private AIRoutePlan parseAiPlan(String aiText) throws Exception {
        String json = aiText.replaceAll("(?s)^```json\\s*|\\s*```$", "").trim();
        AIRoutePlan plan = objectMapper.readValue(json, AIRoutePlan.class);
        validateAiPlan(plan);
        return plan;
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

    private List<Attraction> loadCandidateAttractions(List<String> candidatePoiIds) {
        if (candidatePoiIds == null || candidatePoiIds.isEmpty()) {
            return List.of();
        }

        List<Attraction> loaded = attractionMapper.selectByPoiIds(candidatePoiIds);
        if (loaded == null || loaded.isEmpty()) {
            return List.of();
        }

        Map<String, Attraction> attractionMap = new HashMap<>();
        for (Attraction attraction : loaded) {
            if (attraction == null || attraction.getPoiId() == null || attraction.getPoiId().isBlank()) {
                continue;
            }
            attractionMap.put(attraction.getPoiId(), attraction);
        }

        List<Attraction> list = new ArrayList<>();
        LinkedHashSet<String> uniquePoiIds = new LinkedHashSet<>(candidatePoiIds);
        for (String poiId : uniquePoiIds) {
            Attraction attraction = attractionMap.get(poiId);
            if (attraction != null && attraction.getLocation() != null && !attraction.getLocation().isBlank()) {
                list.add(attraction);
            }
        }

        if (list.size() > 15) {
            list = new ArrayList<>(list.subList(0, 15));
        }
        return list;
    }

    private List<Attraction> loadFallbackAttractions(int limit) {
        List<Attraction> all = attractionMapper.selectAll();
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<Attraction> filtered = new ArrayList<>();
        for (Attraction attraction : all) {
            if (attraction == null || attraction.getPoiId() == null || attraction.getPoiId().isBlank()) {
                continue;
            }
            if (attraction.getLocation() == null || attraction.getLocation().isBlank()) {
                continue;
            }
            filtered.add(attraction);
            if (filtered.size() >= limit) {
                break;
            }
        }
        return filtered;
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

    private String buildFinalPrompt(String userMessage, List<Attraction> candidates,
                                    List<AmapTransitClient.TransitEdge> matrix, List<String> retrievedTexts) {
        StringBuilder sb = new StringBuilder();
        sb.append(userMessage).append("\n\n");
        sb.append("向量检索原始文本片段:\n");
        if (retrievedTexts == null || retrievedTexts.isEmpty()) {
            sb.append("无\n\n");
        } else {
            for (int i = 0; i < retrievedTexts.size(); i++) {
                sb.append(i + 1).append(". ").append(retrievedTexts.get(i)).append("\n");
            }
            sb.append("\n");
        }

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
