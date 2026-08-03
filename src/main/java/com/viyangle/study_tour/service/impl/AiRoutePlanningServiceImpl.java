package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.aiservice.RouteComposerService;
import com.viyangle.study_tour.graph.KnowledgeGraph;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.mapper.TagMapper;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.pojo.RouteConstraintState;
import com.viyangle.study_tour.pojo.Tag;
import com.viyangle.study_tour.pojo.VectorRetrievalResult;
import com.viyangle.study_tour.service.AiRoutePlanningService;
import com.viyangle.study_tour.service.KnowledgeGraphRecommendService;
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
    private KnowledgeGraphRecommendService kgRecommendService;

    @Autowired
    private KnowledgeGraph knowledgeGraph;

    @Autowired
    private AccountTagPrefMapper accountTagPrefMapper;

    @Autowired
    private TagMapper tagMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public AIRoutePlan planRouteV2(String memoryId, String message, Long accountId) throws Exception {
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

        // 知识图谱补充召回：从图谱中按标签+地区检索候选景点
        List<String> kgPoiIds = retrieveCandidatesFromGraph(message, state, accountId);
        if (!kgPoiIds.isEmpty()) {
            // 合并向量检索和图谱检索的结果，去重
            LinkedHashSet<String> merged = new LinkedHashSet<>(candidatePoiIds);
            merged.addAll(kgPoiIds);
            candidatePoiIds = new ArrayList<>(merged);
        }
        
        // 计算 PPR 分数（用于后续排序）
        Map<String, Double> pprScores = computePPRScores(message, accountId);
        
        List<Attraction> candidates = loadCandidateAttractions(candidatePoiIds);
        
        // 用迭代 PPR 进行去重排序（替代 MMR）
        if (!pprScores.isEmpty()) {
            log.info("使用迭代 PPR 对候选景点进行多样性排序，目标数量：10");
            candidates = iterativePPRSelection(candidates, pprScores, 10, state.getExcludePoiIds());
            log.info("AI 路线规划调试：经迭代 PPR 排序后候选数量={}", candidates.size());
        }
        
        log.info("AI路线规划调试: 向量+图谱候选数量={}", candidates.size());
        if (candidates.size() < 2) {
            candidates = loadFallbackAttractions(15);
            log.info("AI路线规划调试: fallback候选数量={}", candidates.size());
            candidates = applyStateFilterOnAttractions(candidates, state);
            log.info("AI路线规划调试: 过滤后候选数量={}, excludePoiIds={}", candidates.size(), state.getExcludePoiIds());
        }

        if (candidates.size() < 2) {
            throw new IllegalArgumentException("Candidate POIs are not enough for routing after applying constraints. 当前候选数: " + candidates.size());
        }

        List<AmapTransitClient.TransitEdge> matrix = amapTransitClient.buildUndirectedMatrix(candidates);
        
        // 提取用户偏好标签，用于 prompt 中说明
        List<String> userPrefTags = extractUserPreferenceTags(accountId);
        
        String finalPrompt = buildFinalPrompt(message, state, candidates, matrix, retrievalResult.getRetrievedTexts(), userPrefTags);
        String finalText = routeComposerService.chat(finalPrompt);

        AIRoutePlan plan = parseAiPlan(finalText);
        validateFinalItems(plan.getItems(), candidates, state, message);

        updateStateAfterPlan(state, message, plan);
        saveState(memoryId, state);
        logPlanningDebug(memoryId, retrievalQuery, retrievalResult, candidatePoiIds, state, plan);
        return plan;
    }

    @Override
    public AIRoutePlan optimizeSubmittedRoute(List<RouteAttraction> routeAttractions, String message) throws Exception {
        List<RouteAttraction> submitted = validateSubmittedRoute(routeAttractions);
        List<String> submittedPoiIds = submitted.stream()
                .map(item -> item.getPoiId().trim())
                .toList();

        List<Attraction> loaded = attractionMapper.selectActiveByPoiIds(submittedPoiIds);
        Map<String, Attraction> attractionByPoiId = loaded == null ? Map.of() : loaded.stream()
                .filter(attraction -> attraction != null && attraction.getPoiId() != null)
                .collect(Collectors.toMap(
                        attraction -> attraction.getPoiId().trim().toUpperCase(Locale.ROOT),
                        attraction -> attraction,
                        (first, ignored) -> first
                ));

        List<Attraction> candidates = new ArrayList<>(submittedPoiIds.size());
        for (String poiId : submittedPoiIds) {
            Attraction attraction = attractionByPoiId.get(poiId.toUpperCase(Locale.ROOT));
            if (attraction == null) {
                throw new IllegalArgumentException("Submitted poiId does not exist or is inactive: " + poiId);
            }
            candidates.add(attraction);
        }

        List<AmapTransitClient.TransitEdge> matrix = amapTransitClient.buildUndirectedMatrix(candidates);
        String prompt = buildSubmittedRouteOptimizationPrompt(submitted, message, candidates, matrix);
        AIRoutePlan plan = parseAiPlan(routeComposerService.chat(prompt));
        validateSubmittedOptimization(plan.getItems(), submittedPoiIds);
        return plan;
    }

    private List<RouteAttraction> validateSubmittedRoute(List<RouteAttraction> routeAttractions) {
        if (routeAttractions == null || routeAttractions.size() < 2) {
            throw new IllegalArgumentException("At least two route attractions are required for optimization");
        }
        if (routeAttractions.size() > 20) {
            throw new IllegalArgumentException("At most 20 route attractions can be optimized at once");
        }

        Set<String> poiIds = new LinkedHashSet<>();
        for (int i = 0; i < routeAttractions.size(); i++) {
            RouteAttraction item = routeAttractions.get(i);
            if (item == null || item.getPoiId() == null || item.getPoiId().isBlank()) {
                throw new IllegalArgumentException("Row " + (i + 1) + ": invalid poiId");
            }
            String normalizedPoiId = item.getPoiId().trim().toUpperCase(Locale.ROOT);
            if (!poiIds.add(normalizedPoiId)) {
                throw new IllegalArgumentException("Duplicated poiId: " + item.getPoiId());
            }
        }
        return routeAttractions;
    }

    private String buildSubmittedRouteOptimizationPrompt(List<RouteAttraction> submitted,
                                                         String message,
                                                         List<Attraction> candidates,
                                                         List<AmapTransitClient.TransitEdge> matrix) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：优化用户已经提交的完整路线。\n");
        sb.append("用户补充要求：")
                .append(message == null || message.isBlank() ? "优先减少通勤，并结合开放时间合理安排游览时间。" : message.trim())
                .append("\n\n");
        List<AIRouteItem> originalItems = submitted.stream()
                .map(item -> new AIRouteItem(
                        item.getPoiId(),
                        item.getVisitOrder(),
                        item.getVisitTime() == null ? null : item.getVisitTime().toString(),
                        item.getRecommendedDuration(),
                        item.getNotes()
                ))
                .toList();
        sb.append("原始路线节点(JSON，包含用户已有的顺序和时间信息)：\n")
                .append(objectMapper.writeValueAsString(originalItems))
                .append("\n\n");

        sb.append("必须全部保留的景点：\n");
        for (int i = 0; i < candidates.size(); i++) {
            Attraction attraction = candidates.get(i);
            sb.append(i + 1)
                    .append(". poiId=").append(attraction.getPoiId())
                    .append(", name=").append(nonNull(attraction.getName()))
                    .append(", adcode=").append(nonNull(attraction.getAdcode()))
                    .append(", type=").append(nonNull(attraction.getType()))
                    .append(", opentimeToday=").append(nonNull(attraction.getOpentimeToday()))
                    .append(", opentimeWeek=").append(nonNull(attraction.getOpentimeWeek()))
                    .append("\n");
        }

        sb.append("\n景点交通矩阵(无向)：\n");
        for (AmapTransitClient.TransitEdge edge : matrix) {
            sb.append(edge.getFromPoiId()).append(" <-> ").append(edge.getToPoiId())
                    .append(" | routeDistanceM=").append(edge.getRouteDistanceM())
                    .append(", bestTransitDistanceM=").append(edge.getBestTransitDistanceM())
                    .append(", bestWalkingDistanceM=").append(edge.getBestWalkingDistanceM())
                    .append(", lines=").append(edge.getBestLines() == null ? "[]" : edge.getBestLines())
                    .append("\n");
        }

        sb.append("\n本次优化的硬性约束：\n")
                .append("1. 输出必须且只能包含上面列出的全部景点，每个poiId恰好出现一次，禁止新增、删除或替换景点。\n")
                .append("2. 可以调整visitOrder、visitTime、recommendedDuration和notes。\n")
                .append("3. visitOrder必须从1开始连续递增。\n")
                .append("4. 在满足用户补充要求和开放时间的前提下，尽量减少折返和通勤成本。\n");
        return sb.toString();
    }

    private void validateSubmittedOptimization(List<AIRouteItem> optimizedItems, List<String> submittedPoiIds) {
        if (optimizedItems == null || optimizedItems.size() != submittedPoiIds.size()) {
            throw new IllegalArgumentException("Optimized route must keep every submitted attraction");
        }

        Set<String> expected = submittedPoiIds.stream()
                .map(poiId -> poiId.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> actual = optimizedItems.stream()
                .map(AIRouteItem::getPoiId)
                .filter(poiId -> poiId != null && !poiId.isBlank())
                .map(poiId -> poiId.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!expected.equals(actual) || actual.size() != optimizedItems.size()) {
            throw new IllegalArgumentException("Optimized route changed the submitted attraction set");
        }

        Set<Integer> expectedOrders = new LinkedHashSet<>();
        for (int order = 1; order <= optimizedItems.size(); order++) {
            expectedOrders.add(order);
        }
        Set<Integer> actualOrders = optimizedItems.stream()
                .map(AIRouteItem::getVisitOrder)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!expectedOrders.equals(actualOrders)) {
            throw new IllegalArgumentException("Optimized visitOrder must start at 1 and be continuous");
        }
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

        List<Attraction> loaded = attractionMapper.selectActiveByPoiIds(candidatePoiIds);
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
        List<Attraction> all = attractionMapper.selectAllActive();
        log.info("loadFallbackAttractions: 数据库总景点数={}", all == null ? 0 : all.size());
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<Attraction> filtered = new ArrayList<>();
        int noPoiId = 0, noLocation = 0;
        for (Attraction attraction : all) {
            if (attraction == null || attraction.getPoiId() == null || attraction.getPoiId().isBlank()) {
                noPoiId++;
                continue;
            }
            if (attraction.getLocation() == null || attraction.getLocation().isBlank()) {
                noLocation++;
                continue;
            }
            filtered.add(attraction);
            if (filtered.size() >= limit) {
                break;
            }
        }
        log.info("loadFallbackAttractions: 过滤统计: noPoiId={}, noLocation={}, 有效={}", noPoiId, noLocation, filtered.size());
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
                                    List<String> retrievedTexts,
                                    List<String> userPrefTags) {
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

        // 用户偏好标签（长期兴趣）
        if (userPrefTags != null && !userPrefTags.isEmpty()) {
            sb.append("【用户背景】\n");
            sb.append("- 长期偏好标签：[").append(String.join(", ", userPrefTags)).append("]\n");
            sb.append("- 本次需求关键词：从上方“用户最新需求”中提取\n");
            sb.append("- 编排原则：以本次需求为主导，同时适当兼顾用户长期偏好。如果本次需求与长期偏好冲突，优先满足本次需求。\n\n");
        }

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
                    .append(", opentimeToday=").append(nonNull(a.getOpentimeToday()))
                    .append(", opentimeWeek=").append(nonNull(a.getOpentimeWeek()))
                    .append(", tel=").append(nonNull(a.getTel()))
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

        sb.append("\n补充约束:\n");
        sb.append("1. 必须遵守excludePoiIds和excludeNameKeywords。\n");
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

    /**
     * 提取用户偏好标签（从 account_tag_pref 表）
     */
    private List<String> extractUserPreferenceTags(Long accountId) {
        if (accountId == null) {
            return List.of();
        }
        try {
            List<AccountTagPref> userPrefs = accountTagPrefMapper.selectByAccountId(accountId);
            if (userPrefs == null || userPrefs.isEmpty()) {
                return List.of();
            }
            List<String> tagNames = new ArrayList<>();
            for (AccountTagPref pref : userPrefs) {
                Tag tag = tagMapper.selectById(pref.getTagId());
                if (tag != null && ALLOWED_TAGS.contains(tag.getName())) {
                    tagNames.add(tag.getName());
                }
            }
            return tagNames;
        } catch (Exception e) {
            log.warn("Failed to extract user preference tags for accountId={}", accountId, e);
            return List.of();
        }
    }

    private String nonNull(String s) {
        return s == null ? "" : s;
    }

    /**
     * 计算 PPR 分数：从用户本次意图 + 长期偏好的景点出发，做多跳随机游走。
     */
    private Map<String, Double> computePPRScores(String message, Long accountId) {
        if (knowledgeGraph == null || !knowledgeGraph.isLoaded()) {
            return Map.of();
        }
        try {
            // 1. 确定个人节点集合（本次意图 + 长期偏好）
            Set<String> personalNodes = new HashSet<>();
            
            // 本次意图匹配的景点
            for (String tag : ALLOWED_TAGS) {
                if (message != null && message.contains(tag)) {
                    Set<String> tagAttractions = knowledgeGraph.getAttractionsByTags(List.of(tag));
                    personalNodes.addAll(tagAttractions);
                }
            }
            
            // 长期偏好匹配的景点
            if (accountId != null) {
                List<AccountTagPref> userPrefs = accountTagPrefMapper.selectByAccountId(accountId);
                if (userPrefs != null && !userPrefs.isEmpty()) {
                    for (AccountTagPref pref : userPrefs) {
                        Tag tag = tagMapper.selectById(pref.getTagId());
                        if (tag != null && ALLOWED_TAGS.contains(tag.getName())) {
                            Set<String> tagAttractions = knowledgeGraph.getAttractionsByTags(List.of(tag.getName()));
                            personalNodes.addAll(tagAttractions);
                        }
                    }
                }
            }
            
            if (personalNodes.isEmpty()) {
                log.info("PPR: 无个人节点，跳过计算");
                return Map.of();
            }
            
            // 2. 跑 PPR
            log.info("PPR: 从{}个个人节点出发计算", personalNodes.size());
            Map<String, Double> pprScores = knowledgeGraph.personalizedPageRank(personalNodes, 30, 0.85);
            
            // 3. 过滤掉分数太低的（噪音）
            double threshold = 0.001;
            Map<String, Double> filtered = pprScores.entrySet().stream()
                    .filter(e -> e.getValue() >= threshold)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            
            log.info("PPR: 计算完成，有效节点数={}", filtered.size());
            return filtered;
            
        } catch (Exception e) {
            log.warn("PPR 计算失败: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * 综合打分：标签匹配度（基础分）+ PPR 分数（多跳相关性）
     * @param poiId 景点ID
     * @param pprScores PPR 分数映射
     * @return 综合分数（越高越相关）
     */
    private double getCombinedScore(String poiId, Map<String, Double> pprScores) {
        // 标签匹配度作为基础分（这里简化为 1.0，实际可以从 retrieveCandidatesFromGraph 里传过来）
        double tagMatchScore = 1.0;
        
        // PPR 分数（可能为空）
        double pprScore = pprScores.getOrDefault(poiId, 0.0);
        
        // 融合：60% 标签匹配 + 40% PPR
        double lambda = 0.6;
        return lambda * tagMatchScore + (1 - lambda) * pprScore * 10; // PPR 放大 10 倍以平衡量级
    }

    /**
     * 从知识图谱中检索候选景点。
     * 从用户消息中提取标签和地区信息，调用图谱服务检索。
     * @param accountId 用户ID（可选），用于融合个人偏好标签
     */
    private List<String> retrieveCandidatesFromGraph(String message, RouteConstraintState state, Long accountId) {
        if (kgRecommendService == null) {
            return List.of();
        }
        try {
            // 从消息中提取匹配的研学标签
            List<String> matchedTags = new ArrayList<>();
            for (String tag : ALLOWED_TAGS) {
                if (message != null && message.contains(tag)) {
                    matchedTags.add(tag);
                }
            }

            // 从历史消息中也提取标签
            if (state.getUserMessages() != null) {
                for (String msg : state.getUserMessages()) {
                    for (String tag : ALLOWED_TAGS) {
                        if (msg.contains(tag) && !matchedTags.contains(tag)) {
                            matchedTags.add(tag);
                        }
                    }
                }
            }

            // 融合用户个人偏好标签
            if (accountId != null) {
                List<AccountTagPref> userPrefs = accountTagPrefMapper.selectByAccountId(accountId);
                if (userPrefs != null && !userPrefs.isEmpty()) {
                    for (AccountTagPref pref : userPrefs) {
                        Tag tag = tagMapper.selectById(pref.getTagId());
                        if (tag != null && ALLOWED_TAGS.contains(tag.getName()) && !matchedTags.contains(tag.getName())) {
                            matchedTags.add(tag.getName());
                        }
                    }
                    log.info("用户{}有{}个偏好标签，已融合到matchedTags", accountId, userPrefs.size());
                }
            }

            // 地区暂时从消息中简单提取（后续可扩展）
            String regionCode = null;

            if (matchedTags.isEmpty() && regionCode == null) {
                return List.of();
            }

            List<Attraction> kgCandidates = kgRecommendService.retrieveCandidatesByGraph(matchedTags, regionCode, 15);
            List<String> kgPoiIds = applyStateFilterOnPoiIds(
                    kgCandidates.stream().map(Attraction::getPoiId).toList(), state);
            log.info("KG candidate retrieval: matchedTags={}, kgPoiIds={}", matchedTags, kgPoiIds);
            return kgPoiIds;
        } catch (Exception e) {
            log.warn("KG candidate retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }
}
