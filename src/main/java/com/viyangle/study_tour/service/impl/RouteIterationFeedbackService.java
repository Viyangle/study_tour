package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.mapper.RouteAttractionMapper;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.pojo.TagDimension;
import com.viyangle.study_tour.pojo.TagFeedbackEvent;
import com.viyangle.study_tour.service.TagFeedbackEventType;
import com.viyangle.study_tour.service.TagFeedbackService;
import com.viyangle.study_tour.service.TagVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从「同一 memoryId 的多次 AI 路线生成 / 用户手动编辑」中学习景点-标签匹配。
 *
 * <p>过程轮次不直接给景点加减分，只记录本轮出现过哪些景点、对应的 tag 意图；
 * 最终发布时统一结算：</p>
 * <ul>
 *   <li>最终路线上的景点：{@code ROUTE_STOP_CONFIRM +0.8}；</li>
 *   <li>过程中出现过、但最终没进路线：{@code ROUTE_STOP_REJECT -0.2}。</li>
 * </ul>
 * <p>降低幅度小于提高幅度，只有最终确定的结果才获得强正向证据。</p>
 */
@Slf4j
@Service
public class RouteIterationFeedbackService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired(required = false)
    private TagFeedbackService tagFeedbackService;

    @Autowired(required = false)
    private TagVectorService tagVectorService;

    @Autowired(required = false)
    private RouteAttractionMapper routeAttractionMapper;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<Long, String> routeSessions = new ConcurrentHashMap<>();

    public void recordPlan(Long accountId, String memoryId, AIRoutePlan plan) {
        if (plan == null || plan.getItems() == null || plan.getItems().isEmpty()) {
            return;
        }
        Set<String> currentPois = new LinkedHashSet<>();
        for (AIRouteItem item : plan.getItems()) {
            if (item != null && item.getPoiId() != null && !item.getPoiId().isBlank()) {
                currentPois.add(item.getPoiId().trim());
            }
        }
        if (currentPois.isEmpty()) {
            return;
        }
        List<TagDimension> dimensions = dimensions();
        if (dimensions.isEmpty()) {
            return;
        }
        updateSession(accountId, memoryId, currentPois,
                intent(plan.getTagVector(), plan.getTag(), dimensions));
    }

    /**
     * 用户手动编辑/提交路线后调用：只记录本轮出现过什么，不直接加减分。
     */
    public void recordSubmission(Long accountId,
                                 String memoryId,
                                 Collection<String> submittedPoiIds,
                                 Map<String, Double> tagVector,
                                 String tag) {
        if (submittedPoiIds == null || submittedPoiIds.isEmpty()) {
            return;
        }
        Set<String> currentPois = new LinkedHashSet<>();
        for (String poiId : submittedPoiIds) {
            if (poiId != null && !poiId.isBlank()) {
                currentPois.add(poiId.trim());
            }
        }
        if (currentPois.isEmpty()) {
            return;
        }
        List<TagDimension> dimensions = dimensions();
        if (dimensions.isEmpty()) {
            return;
        }
        updateSession(accountId, memoryId, currentPois,
                intent(tagVector, tag, dimensions));
    }

    /**
     * 候选列表曝光：只记录该 tag 下得分 > 0 的候选，最终没被选时给 -0.02 轻降。
     */
    public void recordExposure(Long accountId,
                               String memoryId,
                               Collection<String> exposedPoiIds,
                               Map<String, Double> tagVector,
                               String tag) {
        if (exposedPoiIds == null || exposedPoiIds.isEmpty()
                || memoryId == null || memoryId.isBlank()) {
            return;
        }
        List<TagDimension> dimensions = dimensions();
        if (dimensions.isEmpty()) {
            return;
        }
        double[] intent = intent(tagVector, tag, dimensions);
        if (sum(intent) <= 1e-9) {
            return;
        }
        Session session = sessions.computeIfAbsent(key(accountId, memoryId), ignored -> new Session());
        session.accountId = accountId;
        for (String poiId : exposedPoiIds) {
            if (poiId == null || poiId.isBlank()) {
                continue;
            }
            String normalized = poiId.trim();
            if (tagVectorService.score(normalized, intent) > 0) {
                session.exposed.put(normalized, intent);
            }
        }
    }

    /** 路线保存后绑定 routeId，最终发布项目时按 routeId 结算。 */
    public void bindRoute(Long routeId, Long accountId, String memoryId) {
        if (routeId == null || memoryId == null || memoryId.isBlank()) {
            return;
        }
        String key = key(accountId, memoryId);
        if (sessions.containsKey(key)) {
            routeSessions.put(routeId, key);
        }
    }

    /** 项目发布时调用：最终路线上的景点正向，过程中出现但未进入最终路线的轻降。 */
    public void finalizeByRouteId(Long routeId) {
        if (routeId == null) {
            return;
        }
        String key = routeSessions.remove(routeId);
        if (key == null) {
            return;
        }
        Session session = sessions.get(key);
        if (session == null) {
            return;
        }

        Set<String> finalPois = new LinkedHashSet<>();
        if (routeAttractionMapper != null) {
            List<RouteAttraction> stops = routeAttractionMapper.selectByRouteId(routeId);
            if (stops != null) {
                for (RouteAttraction stop : stops) {
                    if (stop != null && stop.getPoiId() != null && !stop.getPoiId().isBlank()) {
                        finalPois.add(stop.getPoiId().trim());
                    }
                }
            }
        }
        if (finalPois.isEmpty()) {
            finalPois = session.lastPois;
        }
        finalizeSession(key, finalPois, session.lastIntent);
    }

    /** 显式结算一个会话；主要供项目发布和测试使用。 */
    public void finalizeSession(Long accountId,
                                String memoryId,
                                Collection<String> finalPoiIds,
                                Map<String, Double> tagVector,
                                String tag) {
        if (memoryId == null || memoryId.isBlank()) {
            return;
        }
        List<TagDimension> dimensions = dimensions();
        if (dimensions.isEmpty()) {
            return;
        }
        Set<String> finalPois = new LinkedHashSet<>();
        if (finalPoiIds != null) {
            for (String poiId : finalPoiIds) {
                if (poiId != null && !poiId.isBlank()) {
                    finalPois.add(poiId.trim());
                }
            }
        }
        finalizeSession(key(accountId, memoryId), finalPois,
                intent(tagVector, tag, dimensions));
    }

    private void updateSession(Long accountId,
                               String memoryId,
                               Set<String> currentPois,
                               double[] intent) {
        if (memoryId == null || memoryId.isBlank() || sum(intent) <= 1e-9) {
            return;
        }
        String key = key(accountId, memoryId);
        Session session = sessions.computeIfAbsent(key, ignored -> new Session());
        session.accountId = accountId;
        for (String poiId : currentPois) {
            session.seen.put(poiId, intent);
        }
        session.lastPois = new LinkedHashSet<>(currentPois);
        session.lastIntent = intent;
    }

    private void finalizeSession(String key, Set<String> finalPois, double[] finalIntent) {
        Session session = sessions.remove(key);
        if (session == null) {
            return;
        }
        if (sum(finalIntent) <= 1e-9) {
            finalIntent = session.lastIntent;
        }
        if (sum(finalIntent) <= 1e-9) {
            log.debug("路线会话没有可用 tag 意图，跳过结算: key={}", key);
            return;
        }

        int confirmed = 0;
        for (String poiId : finalPois) {
            confirmed += record(TagFeedbackEventType.ROUTE_STOP_CONFIRM, poiId,
                    finalIntent, key, session.accountId);
        }

        int rejected = 0;
        for (Map.Entry<String, double[]> entry : session.seen.entrySet()) {
            if (finalPois.contains(entry.getKey())) {
                continue;
            }
            rejected += record(TagFeedbackEventType.ROUTE_STOP_REJECT, entry.getKey(),
                    entry.getValue(), key, session.accountId);
        }

        int notSelected = 0;
        for (Map.Entry<String, double[]> entry : session.exposed.entrySet()) {
            if (finalPois.contains(entry.getKey()) || session.seen.containsKey(entry.getKey())) {
                continue;
            }
            notSelected += record(TagFeedbackEventType.ROUTE_STOP_NOT_SELECTED, entry.getKey(),
                    entry.getValue(), key, session.accountId);
        }

        routeSessions.entrySet().removeIf(e -> key.equals(e.getValue()));
        log.info("路线会话结算: key={}, final={}, confirmed={}, rejected={}, notSelected={}",
                key, finalPois.size(), confirmed, rejected, notSelected);
    }

    private int record(TagFeedbackEventType type,
                       String poiId,
                       double[] intent,
                       String sessionKey,
                       Long accountId) {
        TagFeedbackEvent event = new TagFeedbackEvent();
        event.setAccountId(accountId);
        event.setSessionKey(sessionKey(sessionKey));
        event.setEventType(type.name());
        event.setPoiId(poiId);
        event.setEventValue(type.defaultWeight());
        event.setOccurredAt(LocalDateTime.now());
        event.setIdempotencyKey("iter:" + UUID.randomUUID());
        try {
            event.setIntentVector(MAPPER.writeValueAsString(intent));
        } catch (Exception e) {
            log.debug("序列化路线意图失败，跳过事件: poiId={}", poiId);
            return 0;
        }
        return tagFeedbackService.record(event);
    }

    private List<TagDimension> dimensions() {
        if (tagFeedbackService == null || tagVectorService == null || !tagVectorService.isReady()) {
            return List.of();
        }
        return tagVectorService.dimensions();
    }

    private double[] intent(Map<String, Double> tagVector, String tag, List<TagDimension> dimensions) {
        double[] vector = new double[dimensions.size()];
        Map<String, Integer> ordinalByName = new HashMap<>();
        Map<String, Integer> ordinalByCode = new HashMap<>();
        for (int i = 0; i < dimensions.size(); i++) {
            TagDimension dimension = dimensions.get(i);
            if (dimension.getName() != null) {
                ordinalByName.put(dimension.getName(), i);
            }
            if (dimension.getCode() != null) {
                ordinalByCode.put(dimension.getCode(), i);
            }
        }

        if (tagVector != null) {
            for (Map.Entry<String, Double> entry : tagVector.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                String key = entry.getKey().trim();
                Integer ordinal = ordinalByName.get(key);
                if (ordinal == null) {
                    ordinal = ordinalByCode.get(key);
                }
                if (ordinal != null) {
                    vector[ordinal] = Math.max(vector[ordinal], Math.min(1.0, entry.getValue()));
                }
            }
        }
        if (sum(vector) <= 1e-9 && tag != null) {
            Integer ordinal = ordinalByName.get(tag.trim());
            if (ordinal != null) {
                vector[ordinal] = 1.0;
            }
        }
        return vector;
    }

    private String key(Long accountId, String memoryId) {
        return (accountId == null ? "anonymous" : accountId) + ":" + memoryId;
    }

    private String sessionKey(String key) {
        String value = "mem:" + Integer.toHexString(key.hashCode());
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total;
    }

    private static final class Session {
        private final Map<String, double[]> seen = new LinkedHashMap<>();
        private final Map<String, double[]> exposed = new LinkedHashMap<>();
        private Set<String> lastPois = new LinkedHashSet<>();
        private double[] lastIntent = new double[0];
        private Long accountId;
    }
}
