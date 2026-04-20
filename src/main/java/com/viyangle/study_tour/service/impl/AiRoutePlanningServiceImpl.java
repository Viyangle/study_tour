package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.aiservice.RouteComposerService;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.RouteConstraintState;
import com.viyangle.study_tour.pojo.VectorRetrievalResult;
import com.viyangle.study_tour.service.AiRoutePlanningService;
import com.viyangle.study_tour.service.VectorCandidateRetrieverService;
import com.viyangle.study_tour.utils.AmapTransitClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiRoutePlanningServiceImpl implements AiRoutePlanningService {

    private static final String CONSTRAINT_KEY_PREFIX = "ai:route:constraint:";
    private static final Duration CONSTRAINT_TTL = Duration.ofDays(7);
    private static final int MAX_USER_MESSAGE_HISTORY = 12;
    private static final Pattern EXCLUDE_NAME_PATTERN = Pattern.compile("不要\\s*([^，。；,.;\\s]+)");
    private static final Set<String> RESET_KEYWORDS = Set.of("重新开始", "清空", "重置", "new route");
    private static final Set<String> CHANGE_KEYWORDS = Set.of("换", "替换", "不要");

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

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public AIRoutePlan planRouteV2(String memoryId, String message) throws Exception {
        RouteConstraintState state = loadOrCreateState(memoryId, message);
        if (containsAnyKeyword(message, RESET_KEYWORDS)) {
            state = new RouteConstraintState();
        }
        appendUserMessage(state, message);

        mergeExclusionConstraints(state, message);
        enrichExcludePoiIdsFromNames(state);

        String retrievalQuery = buildRetrievalQuery(message, state);
        VectorRetrievalResult retrievalResult = vectorCandidateRetrieverService.retrieveCandidatesWithTexts(retrievalQuery, 40, 3);
        List<String> candidatePoiIds = applyStateFilterOnPoiIds(retrievalResult.getPoiIds(), state);
        List<Attraction> candidates = loadCandidateAttractions(candidatePoiIds);
        if (candidates.size() < 2) {
            candidates = loadFallbackAttractions(15);
            candidates = applyStateFilterOnAttractions(candidates, state);
        }

        if (candidates.size() < 2) {
            throw new IllegalArgumentException("Candidate POIs are not enough for routing after applying constraints.");
        }

        List<AmapTransitClient.TransitEdge> matrix = amapTransitClient.buildUndirectedMatrix(candidates);
        String finalPrompt = buildFinalPrompt(message, state, candidates, matrix, retrievalResult.getRetrievedTexts());
        String finalText = routeComposerService.chat(finalPrompt);

        AIRoutePlan plan = parseAiPlan(finalText);
        validateFinalItems(plan.getItems(), candidates, state, message);

        updateStateAfterPlan(state, message, plan);
        saveState(memoryId, state);
        logPlanningDebug(memoryId, retrievalQuery, retrievalResult, candidatePoiIds, state, plan);
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
        if (list.size() > 20) {
            list = new ArrayList<>(list.subList(0, 20));
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

    private List<String> applyStateFilterOnPoiIds(List<String> poiIds, RouteConstraintState state) {
        if (poiIds == null || poiIds.isEmpty()) {
            return List.of();
        }
        Set<String> excludeSet = normalizeSet(state.getExcludePoiIds());
        List<String> filtered = new ArrayList<>();
        for (String poiId : poiIds) {
            if (poiId == null || poiId.isBlank()) {
                continue;
            }
            String normalizedPoiId = poiId.trim().toUpperCase(Locale.ROOT);
            if (excludeSet.contains(normalizedPoiId)) {
                continue;
            }
            filtered.add(normalizedPoiId);
        }
        return filtered;
    }

    private List<Attraction> applyStateFilterOnAttractions(List<Attraction> attractions, RouteConstraintState state) {
        Set<String> excludePoiIds = normalizeSet(state.getExcludePoiIds());
        List<String> excludeNames = normalizeNames(state.getExcludeNameKeywords());
        List<Attraction> filtered = new ArrayList<>();
        for (Attraction attraction : attractions) {
            if (attraction == null || attraction.getPoiId() == null) {
                continue;
            }
            if (excludePoiIds.contains(attraction.getPoiId().toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (containsAnyName(attraction.getName(), excludeNames)) {
                continue;
            }
            filtered.add(attraction);
        }
        return filtered;
    }

    private void validateFinalItems(List<AIRouteItem> finalItems, List<Attraction> candidates, RouteConstraintState state, String message) {
        Set<String> allowedPoiIds = candidates.stream()
                .map(Attraction::getPoiId)
                .filter(id -> id != null && !id.isBlank())
                .map(id -> id.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> excludedPoiIds = normalizeSet(state.getExcludePoiIds());
        List<String> excludedNames = normalizeNames(state.getExcludeNameKeywords());
        List<String> currentPoiIds = new ArrayList<>();

        Map<String, Attraction> attractionByPoiId = candidates.stream()
                .filter(a -> a.getPoiId() != null)
                .collect(Collectors.toMap(a -> a.getPoiId().toUpperCase(Locale.ROOT), a -> a, (a, b) -> a));

        for (AIRouteItem item : finalItems) {
            String poiId = item.getPoiId() == null ? "" : item.getPoiId().trim().toUpperCase(Locale.ROOT);
            currentPoiIds.add(poiId);
            if (!allowedPoiIds.contains(poiId)) {
                throw new IllegalArgumentException("Final route contains poiId outside candidate set: " + item.getPoiId());
            }
            if (excludedPoiIds.contains(poiId)) {
                throw new IllegalArgumentException("Final route contains excluded poiId: " + item.getPoiId());
            }
            Attraction attraction = attractionByPoiId.get(poiId);
            if (attraction != null && containsAnyName(attraction.getName(), excludedNames)) {
                throw new IllegalArgumentException("Final route contains excluded attraction name: " + attraction.getName());
            }
        }

        if (containsAnyKeyword(message, CHANGE_KEYWORDS)
                && state.getLastRoutePoiIds() != null
                && !state.getLastRoutePoiIds().isEmpty()) {
            Set<String> previous = normalizeSet(state.getLastRoutePoiIds());
            Set<String> current = normalizeSet(currentPoiIds);
            if (previous.equals(current)) {
                throw new IllegalArgumentException("Route has not changed after user requested modification.");
            }
        }
    }

    private String buildFinalPrompt(String userMessage,
                                    RouteConstraintState state,
                                    List<Attraction> candidates,
                                    List<AmapTransitClient.TransitEdge> matrix,
                                    List<String> retrievedTexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户历史输入(按时间顺序):\n");
        if (state.getUserMessages() == null || state.getUserMessages().isEmpty()) {
            sb.append("无\n");
        } else {
            for (int i = 0; i < state.getUserMessages().size(); i++) {
                sb.append(i + 1).append(". ").append(state.getUserMessages().get(i)).append("\n");
            }
        }
        sb.append("\n用户最新需求:\n").append(userMessage).append("\n\n");

        sb.append("约束(JSON):\n");
        sb.append("{");
        sb.append("\"excludePoiIds\":").append(state.getExcludePoiIds());
        sb.append(",\"excludeNameKeywords\":").append(state.getExcludeNameKeywords());
        sb.append(",\"lastRoutePoiIds\":").append(state.getLastRoutePoiIds());
        sb.append("}\n\n");

        sb.append("向量检索片段(仅参考):\n");
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
                    .append(", adcode=").append(nonNull(a.getAdcode()))
                    .append(", type=").append(nonNull(a.getType()))
                    .append("\n");
        }

        sb.append("\n候选景点交通矩阵(无向):\n");
        for (AmapTransitClient.TransitEdge e : matrix) {
            sb.append(e.getFromPoiId()).append(" <-> ").append(e.getToPoiId())
                    .append(" | routeDistanceM=").append(e.getRouteDistanceM())
                    .append(", bestTransitDistanceM=").append(e.getBestTransitDistanceM())
                    .append(", bestWalkingDistanceM=").append(e.getBestWalkingDistanceM())
                    .append(", lines=").append(e.getBestLines() == null ? "[]" : e.getBestLines())
                    .append("\n");
        }

        sb.append("\n严格要求:\n");
        sb.append("1. 输出的每个poiId必须来自候选景点列表。\n");
        sb.append("2. 必须遵守excludePoiIds和excludeNameKeywords。\n");
        sb.append("3. 只输出JSON，不要输出额外解释。\n");
        return sb.toString();
    }

    private RouteConstraintState loadOrCreateState(String memoryId, String message) {
        if (memoryId == null || memoryId.isBlank()) {
            RouteConstraintState state = new RouteConstraintState();
            state.setLastUserMessage(message);
            return state;
        }

        String raw = redisTemplate.opsForValue().get(CONSTRAINT_KEY_PREFIX + memoryId);
        if (raw == null || raw.isBlank()) {
            RouteConstraintState state = new RouteConstraintState();
            state.setLastUserMessage(message);
            return state;
        }

        try {
            return objectMapper.readValue(raw, RouteConstraintState.class);
        } catch (Exception e) {
            log.warn("Failed to parse route constraint state, memoryId={}, fallback to empty state", memoryId, e);
            RouteConstraintState state = new RouteConstraintState();
            state.setLastUserMessage(message);
            return state;
        }
    }

    private void saveState(String memoryId, RouteConstraintState state) {
        if (memoryId == null || memoryId.isBlank() || state == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(CONSTRAINT_KEY_PREFIX + memoryId, json, CONSTRAINT_TTL);
        } catch (Exception e) {
            log.warn("Failed to save route constraint state, memoryId={}", memoryId, e);
        }
    }

    private void mergeExclusionConstraints(RouteConstraintState state, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        Matcher matcher = EXCLUDE_NAME_PATTERN.matcher(message);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name == null) {
                continue;
            }
            String normalized = name.trim();
            if (!normalized.isBlank() && !state.getExcludeNameKeywords().contains(normalized)) {
                state.getExcludeNameKeywords().add(normalized);
            }
        }
    }

    private void enrichExcludePoiIdsFromNames(RouteConstraintState state) {
        List<String> names = normalizeNames(state.getExcludeNameKeywords());
        if (names.isEmpty()) {
            return;
        }
        List<Attraction> all = attractionMapper.selectAll();
        if (all == null || all.isEmpty()) {
            return;
        }
        Set<String> excludePoiIds = normalizeSet(state.getExcludePoiIds());
        for (Attraction attraction : all) {
            if (attraction == null || attraction.getPoiId() == null || attraction.getName() == null) {
                continue;
            }
            String nameLower = attraction.getName().toLowerCase(Locale.ROOT);
            for (String keyword : names) {
                if (nameLower.contains(keyword.toLowerCase(Locale.ROOT))) {
                    excludePoiIds.add(attraction.getPoiId().toUpperCase(Locale.ROOT));
                    break;
                }
            }
        }
        state.setExcludePoiIds(new ArrayList<>(excludePoiIds));
    }

    private void updateStateAfterPlan(RouteConstraintState state, String message, AIRoutePlan plan) {
        List<String> currentPoiIds = plan.getItems().stream()
                .map(AIRouteItem::getPoiId)
                .filter(id -> id != null && !id.isBlank())
                .map(id -> id.toUpperCase(Locale.ROOT))
                .toList();
        state.setLastRoutePoiIds(new ArrayList<>(currentPoiIds));
        state.setLastUserMessage(message);
    }

    private String buildRetrievalQuery(String message, RouteConstraintState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户历史需求: ");
        if (state.getUserMessages() != null && !state.getUserMessages().isEmpty()) {
            sb.append(String.join(" | ", state.getUserMessages()));
        }
        sb.append("\n");
        sb.append("用户当前需求: ").append(nonNull(message)).append("\n");

        if (state.getLastRoutePoiIds() != null && !state.getLastRoutePoiIds().isEmpty()) {
            sb.append("上一版路线poi: ").append(state.getLastRoutePoiIds()).append("\n");
        }
        if (state.getExcludePoiIds() != null && !state.getExcludePoiIds().isEmpty()) {
            sb.append("排除poi: ").append(state.getExcludePoiIds()).append("\n");
        }
        if (state.getExcludeNameKeywords() != null && !state.getExcludeNameKeywords().isEmpty()) {
            sb.append("排除景点关键词: ").append(state.getExcludeNameKeywords()).append("\n");
        }
        return sb.toString();
    }

    private void logPlanningDebug(String memoryId,
                                  String retrievalQuery,
                                  VectorRetrievalResult retrievalResult,
                                  List<String> candidatePoiIds,
                                  RouteConstraintState state,
                                  AIRoutePlan plan) {
        List<String> finalPoiIds = plan.getItems().stream().map(AIRouteItem::getPoiId).toList();
        log.info("AI route planning debug, memoryId={}, retrievalQuery={}", memoryId, retrievalQuery);
        log.info("AI route planning debug, memoryId={}, userMessages={}", memoryId, state.getUserMessages());
        log.info("AI route planning debug, memoryId={}, retrievedPoiIds={}", memoryId, retrievalResult.getPoiIds());
        log.info("AI route planning debug, memoryId={}, filteredCandidatePoiIds={}", memoryId, candidatePoiIds);
        log.info("AI route planning debug, memoryId={}, excludePoiIds={}, excludeNameKeywords={}",
                memoryId, state.getExcludePoiIds(), state.getExcludeNameKeywords());
        log.info("AI route planning debug, memoryId={}, finalPoiIds={}", memoryId, finalPoiIds);
    }

    private void appendUserMessage(RouteConstraintState state, String message) {
        if (state == null || message == null) {
            return;
        }
        String normalized = message.trim();
        if (normalized.isBlank()) {
            return;
        }
        if (state.getUserMessages() == null) {
            state.setUserMessages(new ArrayList<>());
        }
        state.getUserMessages().add(normalized);
        while (state.getUserMessages().size() > MAX_USER_MESSAGE_HISTORY) {
            state.getUserMessages().remove(0);
        }
    }

    private Set<String> normalizeSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> normalizeNames(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private boolean containsAnyName(String attractionName, List<String> names) {
        if (attractionName == null || attractionName.isBlank() || names == null || names.isEmpty()) {
            return false;
        }
        String target = attractionName.toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (target.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAnyKeyword(String message, Set<String> keywords) {
        if (message == null || message.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String nonNull(String s) {
        return s == null ? "" : s;
    }
}
