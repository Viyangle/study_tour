package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.aiservice.OllamaRouteComposerService;
import com.viyangle.study_tour.aiservice.RouteComposerService;
import com.viyangle.study_tour.graph.KnowledgeGraph;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.mapper.TagMapper;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.ReferencePair;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.pojo.RouteConstraintState;
import com.viyangle.study_tour.pojo.Tag;
import com.viyangle.study_tour.pojo.VectorRetrievalResult;
import com.viyangle.study_tour.service.AiRoutePlanningService;
import com.viyangle.study_tour.service.AttractionSyncService;
import com.viyangle.study_tour.service.KnowledgeGraphRecommendService;
import com.viyangle.study_tour.service.ReferencePairService;
import com.viyangle.study_tour.service.VectorCandidateRetrieverService;
import com.viyangle.study_tour.utils.AmapTransitClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Objects;
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
    private static final int MAX_OPTIMIZE_CANDIDATES = 20;
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

    @Autowired(required = false)
    private OllamaRouteComposerService ollamaRouteComposerService;

    @Autowired
    private AttractionMapper attractionMapper;

    @Autowired
    private AttractionSyncService attractionSyncService;

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

    @Autowired
    private ReferencePairService referencePairService;

    /**
     * 是否使用 Ollama 模式。Ollama 模式下不使用 tools（function calling），
     * 改用预查询的 ReferencePair 数据嵌入 prompt。
     */
    @Value("${app.ai.ollama-enabled:false}")
    private boolean ollamaEnabled;

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
        
        // 用 PPR 分数对候选景点做加权排序
        if (!pprScores.isEmpty()) {
            candidates.sort((a, b) -> {
                double scoreA = getCombinedScore(a.getPoiId(), pprScores);
                double scoreB = getCombinedScore(b.getPoiId(), pprScores);
                return Double.compare(scoreB, scoreA); // 降序
            });
            log.info("AI路线规划调试: 经PPR加权排序后候选数量={}", candidates.size());
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
        String finalText;
        if (ollamaEnabled && ollamaRouteComposerService != null) {
            // Ollama 模式：不使用 tools，将参考景点对预先注入 prompt
            String ollamaPrompt = enrichPromptWithReferencePairs(finalPrompt);
            log.info("Ollama模式: 使用不带tools的OllamaRouteComposerService");
            finalText = ollamaRouteComposerService.chat(ollamaPrompt);
        } else {
            finalText = routeComposerService.chat(finalPrompt);
        }

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

        // 前端可能提交高德搜索到、但后端景点表尚未收录的 POI。
        // 这里把这类 POI 自动登记（或复活）到 attractions 表，保证后续保存路线不受外键限制。
        List<Attraction> submittedAttractions = loadOrRegisterAttractions(submitted);

        // 补充可新增的候选景点：向量召回 + 同地区景点，总候选不超过 20 个，控制交通矩阵规模。
        List<Attraction> addableCandidates = loadOptimizeAddableCandidates(submitted, submittedAttractions, message);
        List<Attraction> candidates = mergeOptimizeCandidates(submittedAttractions, addableCandidates);

        List<AmapTransitClient.TransitEdge> matrix = amapTransitClient.buildUndirectedMatrix(candidates);
        String prompt = buildSubmittedRouteOptimizationPrompt(submitted, message, candidates, matrix);
        String aiText;
        if (ollamaEnabled && ollamaRouteComposerService != null) {
            aiText = ollamaRouteComposerService.chat(prompt);
        } else {
            aiText = routeComposerService.chat(prompt);
        }
        AIRoutePlan plan = parseAiPlan(aiText);
        Set<String> allowedPoiIds = candidates.stream()
                .map(Attraction::getPoiId)
                .filter(poiId -> poiId != null && !poiId.isBlank())
                .map(poiId -> poiId.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        validateSubmittedOptimization(plan.getItems(), allowedPoiIds);
        return plan;
    }

    private List<RouteAttraction> validateSubmittedRoute(List<RouteAttraction> routeAttractions) {
        if (routeAttractions == null || routeAttractions.isEmpty()) {
            throw new IllegalArgumentException("At least one route attraction is required for optimization");
        }
        if (routeAttractions.size() > MAX_OPTIMIZE_CANDIDATES) {
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
        sb.append("任务：优化用户已经提交的完整路线，允许调整景点集合（删除不合适景点、从可选列表新增景点）。\n");
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

        Set<String> submittedKeys = submitted.stream()
                .map(RouteAttraction::getPoiId)
                .filter(poiId -> poiId != null && !poiId.isBlank())
                .map(poiId -> poiId.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        sb.append("可选景点列表(标注[当前路线]的是用户已选景点，其余为可新增景点)：\n");
        for (int i = 0; i < candidates.size(); i++) {
            Attraction attraction = candidates.get(i);
            boolean current = attraction != null && attraction.getPoiId() != null
                    && submittedKeys.contains(attraction.getPoiId().trim().toUpperCase(Locale.ROOT));
            sb.append(i + 1)
                    .append(". poiId=").append(attraction.getPoiId())
                    .append(", name=").append(nonNull(attraction.getName()))
                    .append(", adcode=").append(nonNull(attraction.getAdcode()))
                    .append(", type=").append(nonNull(attraction.getType()))
                    .append(", opentimeToday=").append(nonNull(attraction.getOpentimeToday()))
                    .append(", opentimeWeek=").append(nonNull(attraction.getOpentimeWeek()))
                    .append(current ? " [当前路线]" : "")
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
                .append("1. 输出必须只使用上面可选景点列表中的poiId，禁止使用列表之外的poiId。\n")
                .append("2. 允许增删：可以删除当前路线中不合适的景点，也可以从可选列表中新增景点；最终路线保留1~20个景点，每个poiId恰好出现一次。\n")
                .append("3. 如果用户没有明确要求增删，应尽量保留当前路线的全部景点，只调整visitOrder、visitTime、recommendedDuration和notes；只有景点明显不合理时才删除。\n")
                .append("4. visitOrder必须从1开始连续递增。\n")
                .append("5. 在满足用户补充要求和开放时间的前提下，尽量减少折返和通勤成本。\n");
        return sb.toString();
    }

    private void validateSubmittedOptimization(List<AIRouteItem> optimizedItems, Set<String> allowedPoiIds) {
        if (optimizedItems == null || optimizedItems.isEmpty()) {
            throw new IllegalArgumentException("Optimized route must not be empty");
        }
        if (optimizedItems.size() > MAX_OPTIMIZE_CANDIDATES) {
            throw new IllegalArgumentException("Optimized route cannot contain more than 20 attractions");
        }

        Set<String> actual = optimizedItems.stream()
                .map(AIRouteItem::getPoiId)
                .filter(poiId -> poiId != null && !poiId.isBlank())
                .map(poiId -> poiId.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (actual.size() != optimizedItems.size()) {
            throw new IllegalArgumentException("Optimized route contains duplicated or invalid poiId");
        }
        for (String poiId : actual) {
            if (!allowedPoiIds.contains(poiId)) {
                throw new IllegalArgumentException("Optimized route contains poiId outside candidate set: " + poiId);
            }
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

    /**
     * 把提交的路线节点转成景点数据。已收录的景点直接复用；
     * 未收录的高德 POI 用请求携带的景点字段（必要时用高德 place/detail 兜底）登记到 attractions 表。
     */
    private List<Attraction> loadOrRegisterAttractions(List<RouteAttraction> submitted) {
        List<String> poiIds = submitted.stream()
                .map(RouteAttraction::getPoiId)
                .filter(poiId -> poiId != null && !poiId.isBlank())
                .map(String::trim)
                .toList();

        Map<String, Attraction> attractionByPoiId = new HashMap<>();
        if (!poiIds.isEmpty()) {
            List<Attraction> loaded = attractionMapper.selectByPoiIds(poiIds);
            if (loaded != null) {
                for (Attraction attraction : loaded) {
                    if (attraction != null && attraction.getPoiId() != null && !attraction.getPoiId().isBlank()) {
                        attractionByPoiId.put(attraction.getPoiId().trim().toUpperCase(Locale.ROOT), attraction);
                    }
                }
            }
        }

        List<Attraction> result = new ArrayList<>(submitted.size());
        for (RouteAttraction routeAttraction : submitted) {
            String poiId = routeAttraction.getPoiId().trim();
            String key = poiId.toUpperCase(Locale.ROOT);
            Attraction existing = attractionByPoiId.get(key);
            if (existing == null) {
                // 优先走“高德取数 -> MySQL upsert -> Redis 向量索引”一条龙；
                // 高德不可用时才回退用请求里携带的景点字段登记，保证接口可用。
                Attraction registered = null;
                try {
                    registered = attractionSyncService.syncFromAmap(poiId);
                } catch (Exception e) {
                    log.warn("AMap sync failed for poiId={}, fallback to request metadata: {}",
                            poiId, e.getMessage());
                }
                if (registered == null) {
                    registered = buildAttractionFromRouteAttraction(routeAttraction);
                    if (registered == null) {
                        registered = new Attraction();
                    }
                    registered.setPoiId(poiId);
                    registered.setStatus("ACTIVE");
                    attractionMapper.upsert(registered);
                }
                result.add(registered);
            } else {
                Attraction merged = mergeFromRouteAttraction(existing, routeAttraction);
                boolean active = isActiveAttraction(merged);
                if (!active || hasRouteAttractionMetadata(routeAttraction)) {
                    merged.setStatus("ACTIVE");
                    attractionMapper.upsert(merged);
                }
                result.add(merged);
            }
        }
        return result;
    }

    private Attraction buildAttractionFromRouteAttraction(RouteAttraction ra) {
        if (ra == null || ra.getPoiId() == null || ra.getPoiId().isBlank()) {
            return null;
        }
        Attraction attraction = new Attraction();
        attraction.setPoiId(ra.getPoiId().trim());
        attraction.setParentPoiId(trimToNull(ra.getParentPoiId()));
        attraction.setName(trimToNull(ra.getName()));
        attraction.setAddress(trimToNull(ra.getAddress()));
        attraction.setLocation(trimToNull(ra.getLocation()));
        attraction.setPcode(trimToNull(ra.getPcode()));
        attraction.setPname(trimToNull(ra.getPname()));
        attraction.setCitycode(trimToNull(ra.getCitycode()));
        attraction.setCityname(trimToNull(ra.getCityname()));
        attraction.setAdcode(trimToNull(ra.getAdcode()));
        attraction.setAdname(trimToNull(ra.getAdname()));
        attraction.setType(trimToNull(ra.getType()));
        attraction.setTypecode(trimToNull(ra.getTypecode()));
        attraction.setDistance(trimToNull(ra.getDistance()));
        attraction.setOpentimeToday(trimToNull(ra.getOpentimeToday()));
        attraction.setOpentimeWeek(trimToNull(ra.getOpentimeWeek()));
        attraction.setTel(trimToNull(ra.getTel()));
        return attraction;
    }

    private Attraction mergeFromRouteAttraction(Attraction existing, RouteAttraction ra) {
        if (existing == null) {
            return buildAttractionFromRouteAttraction(ra);
        }
        Attraction requestAttraction = buildAttractionFromRouteAttraction(ra);
        if (requestAttraction != null) {
            mergeAttraction(existing, requestAttraction);
        }
        return existing;
    }

    private void mergeAttraction(Attraction target, Attraction source) {
        if (target == null || source == null) {
            return;
        }
        target.setParentPoiId(fillIfBlank(target.getParentPoiId(), source.getParentPoiId()));
        target.setName(fillIfBlank(target.getName(), source.getName()));
        target.setAddress(fillIfBlank(target.getAddress(), source.getAddress()));
        target.setLocation(fillIfBlank(target.getLocation(), source.getLocation()));
        target.setPcode(fillIfBlank(target.getPcode(), source.getPcode()));
        target.setPname(fillIfBlank(target.getPname(), source.getPname()));
        target.setCitycode(fillIfBlank(target.getCitycode(), source.getCitycode()));
        target.setCityname(fillIfBlank(target.getCityname(), source.getCityname()));
        target.setAdcode(fillIfBlank(target.getAdcode(), source.getAdcode()));
        target.setAdname(fillIfBlank(target.getAdname(), source.getAdname()));
        target.setType(fillIfBlank(target.getType(), source.getType()));
        target.setTypecode(fillIfBlank(target.getTypecode(), source.getTypecode()));
        target.setDistance(fillIfBlank(target.getDistance(), source.getDistance()));
        target.setOpentimeToday(fillIfBlank(target.getOpentimeToday(), source.getOpentimeToday()));
        target.setOpentimeWeek(fillIfBlank(target.getOpentimeWeek(), source.getOpentimeWeek()));
        target.setTel(fillIfBlank(target.getTel(), source.getTel()));
        if (blank(target.getStatus()) && !blank(source.getStatus())) {
            target.setStatus(source.getStatus());
        }
    }

    private boolean hasRouteAttractionMetadata(RouteAttraction ra) {
        return ra != null && (!blank(ra.getName()) || !blank(ra.getLocation()) || !blank(ra.getAdcode())
                || !blank(ra.getCitycode()) || !blank(ra.getType()));
    }

    private String fillIfBlank(String current, String candidate) {
        return blank(current) && !blank(candidate) ? candidate.trim() : current;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 为 optimize 补充可新增的候选景点：先向量召回（结合消息与当前路线），再补同地区景点。
     */
    private List<Attraction> loadOptimizeAddableCandidates(List<RouteAttraction> submitted,
                                                           List<Attraction> submittedAttractions,
                                                           String message) {
        int maxAddable = Math.max(0, MAX_OPTIMIZE_CANDIDATES - submitted.size());
        if (maxAddable <= 0) {
            return List.of();
        }
        Set<String> excluded = submitted.stream()
                .map(RouteAttraction::getPoiId)
                .filter(poiId -> poiId != null && !poiId.isBlank())
                .map(poiId -> poiId.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Attraction> addable = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();

        String retrievalQuery = buildOptimizeRetrievalQuery(submitted, message);
        try {
            VectorRetrievalResult retrievalResult = vectorCandidateRetrieverService.retrieveCandidatesWithTexts(retrievalQuery, 20, 0);
            List<String> retrievedPoiIds = retrievalResult == null || retrievalResult.getPoiIds() == null
                    ? List.of() : retrievalResult.getPoiIds();
            if (!retrievedPoiIds.isEmpty()) {
                List<Attraction> retrieved = loadCandidateAttractions(retrievedPoiIds);
                for (Attraction attraction : retrieved) {
                    addOptimizeCandidate(addable, added, attraction, excluded, maxAddable);
                }
            }
        } catch (Exception e) {
            log.warn("Optimize addable vector retrieval failed: {}", e.getMessage());
        }

        String regionCode = firstSubmittedAdcode(submittedAttractions);
        if (!blank(regionCode)) {
            try {
                List<Attraction> sameRegion = attractionMapper.selectByRegionCode(regionCode);
                if (sameRegion != null) {
                    for (Attraction attraction : sameRegion) {
                        addOptimizeCandidate(addable, added, attraction, excluded, maxAddable);
                    }
                }
            } catch (Exception e) {
                log.warn("Optimize addable region retrieval failed, regionCode={}: {}", regionCode, e.getMessage());
            }
        }
        return addable;
    }

    private void addOptimizeCandidate(List<Attraction> addable,
                                      Set<String> added,
                                      Attraction attraction,
                                      Set<String> excluded,
                                      int maxAddable) {
        if (addable.size() >= maxAddable || attraction == null || attraction.getPoiId() == null
                || attraction.getPoiId().isBlank()) {
            return;
        }
        if (blank(attraction.getLocation()) || !isActiveAttraction(attraction)) {
            return;
        }
        String key = attraction.getPoiId().trim().toUpperCase(Locale.ROOT);
        if (excluded.contains(key) || !added.add(key)) {
            return;
        }
        addable.add(attraction);
    }

    private boolean isActiveAttraction(Attraction attraction) {
        return attraction.getStatus() == null || attraction.getStatus().isBlank()
                || "ACTIVE".equalsIgnoreCase(attraction.getStatus().trim());
    }

    private List<Attraction> mergeOptimizeCandidates(List<Attraction> submittedAttractions,
                                                     List<Attraction> addableCandidates) {
        List<Attraction> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Attraction attraction : submittedAttractions) {
            if (attraction == null || attraction.getPoiId() == null || attraction.getPoiId().isBlank()) {
                continue;
            }
            String key = attraction.getPoiId().trim().toUpperCase(Locale.ROOT);
            if (seen.add(key)) {
                candidates.add(attraction);
            }
        }
        for (Attraction attraction : addableCandidates) {
            if (candidates.size() >= MAX_OPTIMIZE_CANDIDATES || attraction == null
                    || attraction.getPoiId() == null || attraction.getPoiId().isBlank()) {
                continue;
            }
            String key = attraction.getPoiId().trim().toUpperCase(Locale.ROOT);
            if (seen.add(key)) {
                candidates.add(attraction);
            }
        }
        return candidates;
    }

    private String firstSubmittedAdcode(List<Attraction> submittedAttractions) {
        if (submittedAttractions == null) {
            return null;
        }
        for (Attraction attraction : submittedAttractions) {
            if (attraction != null && !blank(attraction.getAdcode())) {
                return attraction.getAdcode().trim();
            }
        }
        return null;
    }

    private String buildOptimizeRetrievalQuery(List<RouteAttraction> submitted, String message) {
        StringBuilder sb = new StringBuilder();
        if (message != null && !message.isBlank()) {
            sb.append("用户优化要求: ").append(message.trim()).append("\n");
        }
        sb.append("当前路线景点: ");
        List<String> names = submitted.stream()
                .filter(Objects::nonNull)
                .map(ra -> !blank(ra.getName()) ? ra.getName() : ra.getPoiId())
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        sb.append(names);
        return sb.toString();
    }

    private AIRoutePlan parseAiPlan(String aiText) throws Exception {
        String json = extractJsonFromResponse(aiText);
        try {
            AIRoutePlan plan = objectMapper.readValue(json, AIRoutePlan.class);
            validateAiPlan(plan);
            return plan;
        } catch (Exception e) {
            // 如果解析失败，记录原始响应以便排查
            log.error("AI返回内容无法解析为JSON，原始响应前500字符: {}",
                    aiText.length() > 500 ? aiText.substring(0, 500) : aiText);
            throw e;
        }
    }

    /**
     * 从模型响应中提取 JSON 对象。
     * 兼容以下情况：
     * 1. 纯 JSON 文本
     * 2. Markdown 代码块包裹的 JSON（```json ... ``` 或 ``` ... ```）
     * 3. 模型在 JSON 前后附加了自然语言说明（Ollama/Qwen 常见行为）
     * 4. 模型返回了多个 JSON 对象（取第一个完整的）
     */
    private String extractJsonFromResponse(String aiText) {
        if (aiText == null || aiText.isBlank()) {
            throw new IllegalArgumentException("AI response is empty");
        }

        String text = aiText.trim();

        // 1. 先尝试去掉 Markdown 代码块标记
        String cleaned = text
                .replaceAll("(?s)^```(?:json)?\\s*", "")  // 开头的 ```json 或 ```
                .replaceAll("(?s)\\s*```$", "")           // 结尾的 ```
                .trim();

        // 2. 找到第一个 { 和最后一个 } 之间的内容（处理模型在 JSON 前后加说明的情况）
        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');

        if (firstBrace == -1 || lastBrace == -1 || firstBrace >= lastBrace) {
            // 没有找到有效的大括号，记录原始内容并抛出异常
            log.error("无法从AI响应中找到JSON对象，响应内容前500字符: {}",
                    text.length() > 500 ? text.substring(0, 500) : text);
            throw new IllegalArgumentException(
                    "AI response does not contain valid JSON object. Response starts with: " +
                    (text.length() > 200 ? text.substring(0, 200) : text));
        }

        String candidate = cleaned.substring(firstBrace, lastBrace + 1);

        // 3. 尝试解析 candidate，如果失败可能是嵌套问题
        //    用更智能的方式匹配：从第一个 { 开始，计数匹配到对应的 }
        try {
            objectMapper.readTree(candidate);  // 验证是否是合法 JSON
            return candidate;
        } catch (Exception e) {
            // candidate 可能因为字符串中包含 } 而导致截断不正确
            // 使用括号计数方式重新提取
            log.debug("简单截取JSON失败，尝试用括号计数方式提取");
        }

        // 4. 括号计数方式：从第一个 { 开始，逐字符扫描
        int startIdx = cleaned.indexOf('{');
        int depth = 0;
        int endIdx = -1;
        for (int i = startIdx; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    endIdx = i;
                    break;
                }
            }
        }

        if (endIdx > startIdx) {
            String bracketMatched = cleaned.substring(startIdx, endIdx + 1);
            log.info("使用括号计数方式成功提取JSON，长度={}", bracketMatched.length());
            return bracketMatched;
        }

        throw new IllegalArgumentException(
                "Unable to extract valid JSON from AI response. Response starts with: " +
                (text.length() > 200 ? text.substring(0, 200) : text));
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

    /**
     * 为 Ollama 模式预查询参考景点对，并嵌入到 prompt 中。
     * 因为 Ollama 不支持 function calling，需要提前查询好数据。
     */
    private String enrichPromptWithReferencePairs(String originalPrompt) {
        try {
            List<ReferencePair> pairs = referencePairService.recommendReferencePairs(null, 1, 10);
            if (pairs == null || pairs.isEmpty()) {
                return originalPrompt;
            }

            StringBuilder sb = new StringBuilder(originalPrompt);
            sb.append("\n\n【参考景点对（已预查询，无需调用工具）】\n");
            sb.append("以下是一些优质的研学景点组合，可作为路线规划的参考：\n");
            for (int i = 0; i < pairs.size(); i++) {
                ReferencePair pair = pairs.get(i);
                sb.append(i + 1).append(". ");
                if (pair.getFromPoiName() != null) {
                    sb.append(pair.getFromPoiName());
                }
                if (pair.getToPoiName() != null) {
                    sb.append(" → ").append(pair.getToPoiName());
                }
                if (pair.getNotes() != null && !pair.getNotes().isBlank()) {
                    sb.append("（").append(pair.getNotes()).append("）");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("预查询参考景点对失败，使用原始prompt: {}", e.getMessage());
            return originalPrompt;
        }
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

    private boolean blank(String s) {
        return s == null || s.isBlank();
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
