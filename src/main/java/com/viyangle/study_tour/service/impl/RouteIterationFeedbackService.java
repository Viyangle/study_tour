package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.mapper.RouteAttractionMapper;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.pojo.TagDimension;
import com.viyangle.study_tour.pojo.TagFeedbackEvent;
import com.viyangle.study_tour.service.AccountTagAffinityService;
import com.viyangle.study_tour.service.TagFeedbackEventType;
import com.viyangle.study_tour.service.TagFeedbackService;
import com.viyangle.study_tour.service.TagImpressionService;
import com.viyangle.study_tour.service.TagVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
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
    private static final String SESSION_PREFIX = "tag:vector:session:";
    private static final String ROUTE_PREFIX = "tag:vector:route:";

    @Autowired(required = false)
    private TagFeedbackService tagFeedbackService;

    @Autowired(required = false)
    private TagVectorService tagVectorService;

    @Autowired(required = false)
    private RouteAttractionMapper routeAttractionMapper;

    @Autowired(required = false)
    private TagImpressionService tagImpressionService;

    @Autowired(required = false)
    private AccountTagAffinityService accountTagAffinityService;

    /** Redis 可用时把会话落到 Redis；不可用时退化为进程内存储。 */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<Long, String> routeSessions = new ConcurrentHashMap<>();

    @Value("${app.tag-vector.session-ttl-hours:168}")
    private long sessionTtlHours = 168;

    @Value("${app.tag-vector.max-sessions:10000}")
    private int maxSessions = 10000;

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
        String sessionKey = key(accountId, memoryId);
        Session session = loadOrCreateSession(sessionKey);
        session.accountId = accountId;
        List<String> shown = new java.util.ArrayList<>();
        for (String poiId : exposedPoiIds) {
            if (poiId == null || poiId.isBlank()) {
                continue;
            }
            String normalized = poiId.trim();
            if (tagVectorService.score(normalized, intent) > 0) {
                session.exposed.put(normalized, intent);
                shown.add(normalized);
            }
        }
        session.touch();
        saveSession(sessionKey, session);
        if (tagImpressionService != null && !shown.isEmpty()) {
            tagImpressionService.record(accountId, sessionKey(sessionKey), "AI_PLAN", shown, null);
        }
    }

    /** 路线保存后绑定 routeId，最终发布项目时按 routeId 结算。 */
    public void bindRoute(Long routeId, Long accountId, String memoryId) {
        if (routeId == null || memoryId == null || memoryId.isBlank()) {
            return;
        }
        String key = key(accountId, memoryId);
        Session session = loadSession(key);
        if (session != null) {
            session.touch();
            saveSession(key, session);
            routeSessions.put(routeId, key);
            saveRouteKey(routeId, key);
        }
    }

    /** 项目发布时调用：最终路线上的景点正向，过程中出现但未进入最终路线的轻降。 */
    public void finalizeByRouteId(Long routeId) {
        if (routeId == null) {
            return;
        }
        String key = loadRouteKey(routeId);
        if (key == null) {
            return;
        }
        Session session = loadSession(key);
        if (session == null) {
            routeSessions.remove(routeId);
            deleteRouteKey(routeId);
            return;
        }
        session.touch();

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
        Session session = loadOrCreateSession(key);
        session.accountId = accountId;
        for (String poiId : currentPois) {
            session.seen.put(poiId, intent);
        }
        session.lastPois = new LinkedHashSet<>(currentPois);
        session.lastIntent = intent;
        session.touch();
        saveSession(key, session);
    }

    private void finalizeSession(String key, Set<String> finalPois, double[] finalIntent) {
        Session session = loadSession(key);
        if (session == null) {
            return;
        }
        session.touch();
        if (tagFeedbackService == null || !tagFeedbackService.isEnabled()) {
            log.debug("反馈采集已关闭，保留路线会话: key={}", key);
            return;
        }
        if (sum(finalIntent) <= 1e-9) {
            finalIntent = session.lastIntent;
        }
        if (sum(finalIntent) <= 1e-9) {
            log.debug("路线会话没有可用 tag 意图，跳过结算: key={}", key);
            deleteSession(key);
            return;
        }

        int confirmed = 0;
        int rejected = 0;
        int notSelected = 0;
        try {
            for (String poiId : finalPois) {
                confirmed += record(TagFeedbackEventType.ROUTE_STOP_CONFIRM, poiId,
                        finalIntent, key, session.accountId);
            }
            for (Map.Entry<String, double[]> entry : session.seen.entrySet()) {
                if (finalPois.contains(entry.getKey())) {
                    continue;
                }
                rejected += record(TagFeedbackEventType.ROUTE_STOP_REJECT, entry.getKey(),
                        entry.getValue(), key, session.accountId);
            }
            for (Map.Entry<String, double[]> entry : session.exposed.entrySet()) {
                if (finalPois.contains(entry.getKey()) || session.seen.containsKey(entry.getKey())) {
                    continue;
                }
                notSelected += record(TagFeedbackEventType.ROUTE_STOP_NOT_SELECTED, entry.getKey(),
                        entry.getValue(), key, session.accountId);
            }
        } catch (Exception e) {
            // 确定性幂等键保证重试不会重复计分；保留会话等待下一次发布/重试。
            log.warn("路线会话结算失败，保留会话等待重试: key={}, {}", key, e.getMessage());
            return;
        }

        // 所有事件都写入成功后才移除会话，避免“先删会话、后写事件失败”导致反馈丢失。
        if (accountTagAffinityService != null) {
            accountTagAffinityService.update(session.accountId, finalIntent);
        }
        deleteSession(key);
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
        event.setSource("SERVER");
        event.setScene("AI_PLAN");
        event.setDimVersion(tagVectorService == null ? null : tagVectorService.dimVersion());
        event.setEventType(type.name());
        event.setPoiId(poiId);
        event.setEventValue(type.defaultWeight());
        event.setOccurredAt(LocalDateTime.now());
        // 同一会话、同一事件类型、同一景点只产生一个事件；结算失败重试时由 DB 唯一键去重。
        String rawKey = sessionKey + "|" + type.name() + "|" + poiId;
        event.setIdempotencyKey("iter:" + UUID.nameUUIDFromBytes(rawKey.getBytes(StandardCharsets.UTF_8)));
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

    private Session loadOrCreateSession(String key) {
        Session session = loadSession(key);
        if (session != null) {
            return session;
        }
        Session created = new Session();
        sessions.put(key, created);
        return created;
    }

    private Session loadSession(String key) {
        if (redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().get(SESSION_PREFIX + key);
                if (json != null && !json.isBlank()) {
                    Session parsed = parseSession(json);
                    if (parsed != null) {
                        sessions.put(key, parsed);
                        return parsed;
                    }
                }
            } catch (Exception e) {
                log.warn("读取路线会话失败，使用进程内缓存: key={}, {}", key, e.getMessage());
            }
        }
        return sessions.get(key);
    }

    private void saveSession(String key, Session session) {
        sessions.put(key, session);
        if (redisTemplate == null) {
            return;
        }
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("accountId", session.accountId);
            state.put("seen", session.seen);
            state.put("exposed", session.exposed);
            state.put("lastPois", session.lastPois);
            state.put("lastIntent", session.lastIntent);
            state.put("updatedAt", session.updatedAt);
            redisTemplate.opsForValue().set(SESSION_PREFIX + key, MAPPER.writeValueAsString(state),
                    Duration.ofHours(Math.max(1, sessionTtlHours)));
        } catch (Exception e) {
            log.warn("写入路线会话失败，仅保留进程内缓存: key={}, {}", key, e.getMessage());
        }
    }

    private void deleteSession(String key) {
        sessions.remove(key);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(SESSION_PREFIX + key);
            } catch (Exception e) {
                log.warn("删除路线会话失败: key={}, {}", key, e.getMessage());
            }
        }
        List<Long> boundRoutes = routeSessions.entrySet().stream()
                .filter(entry -> key.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        for (Long routeId : boundRoutes) {
            routeSessions.remove(routeId);
            deleteRouteKey(routeId);
        }
    }

    private String loadRouteKey(Long routeId) {
        String key = routeSessions.get(routeId);
        if (key != null) {
            return key;
        }
        if (redisTemplate != null) {
            try {
                key = redisTemplate.opsForValue().get(ROUTE_PREFIX + routeId);
                if (key != null) {
                    routeSessions.put(routeId, key);
                }
            } catch (Exception e) {
                log.warn("读取路线-会话映射失败: routeId={}, {}", routeId, e.getMessage());
            }
        }
        return key;
    }

    private void saveRouteKey(Long routeId, String key) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(ROUTE_PREFIX + routeId, key,
                    Duration.ofHours(Math.max(1, sessionTtlHours)));
        } catch (Exception e) {
            log.warn("写入路线-会话映射失败: routeId={}, {}", routeId, e.getMessage());
        }
    }

    private void deleteRouteKey(Long routeId) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(ROUTE_PREFIX + routeId);
        } catch (Exception e) {
            log.warn("删除路线-会话映射失败: routeId={}, {}", routeId, e.getMessage());
        }
    }

    private Session parseSession(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            Session session = new Session();
            session.accountId = root.hasNonNull("accountId") ? root.get("accountId").asLong() : null;
            session.seen = readIntentMap(root.get("seen"));
            session.exposed = readIntentMap(root.get("exposed"));
            session.lastPois = readStringSet(root.get("lastPois"));
            session.lastIntent = readDoubleArray(root.get("lastIntent"));
            session.updatedAt = root.has("updatedAt") ? root.get("updatedAt").asLong() : System.currentTimeMillis();
            return session;
        } catch (Exception e) {
            log.warn("解析路线会话失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, double[]> readIntentMap(JsonNode node) {
        Map<String, double[]> result = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return result;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            result.put(entry.getKey(), readDoubleArray(entry.getValue()));
        }
        return result;
    }

    private double[] readDoubleArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new double[0];
        }
        double[] values = new double[node.size()];
        for (int i = 0; i < node.size(); i++) {
            values[i] = node.get(i).asDouble(0.0);
        }
        return values;
    }

    private Set<String> readStringSet(JsonNode node) {
        Set<String> result = new LinkedHashSet<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode value : node) {
            if (value != null && !value.isNull()) {
                result.add(value.asText());
            }
        }
        return result;
    }

    /**
     * 定期清理长时间未结算的路线会话，避免用户只生成不发布时内存无限增长。
     * Redis 可用时会话本身带 TTL；进程内缓存也按同样 TTL 清理。
     */
    @Scheduled(fixedDelayString = "${app.tag-vector.session-cleanup-interval-ms:600000}")
    public void cleanupSessions() {
        long ttlMillis = Math.max(1, sessionTtlHours) * 3_600_000L;
        long cutoff = System.currentTimeMillis() - ttlMillis;
        sessions.entrySet().removeIf(entry -> entry.getValue().updatedAt < cutoff);

        if (maxSessions > 0 && sessions.size() > maxSessions) {
            int excess = sessions.size() - maxSessions;
            sessions.entrySet().stream()
                    .sorted(Comparator.comparingLong(entry -> entry.getValue().updatedAt))
                    .limit(excess)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(sessions::remove);
        }
        routeSessions.entrySet().removeIf(entry -> !sessions.containsKey(entry.getValue()));
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
        private Map<String, double[]> seen = new LinkedHashMap<>();
        private Map<String, double[]> exposed = new LinkedHashMap<>();
        private Set<String> lastPois = new LinkedHashSet<>();
        private double[] lastIntent = new double[0];
        private Long accountId;
        private long updatedAt = System.currentTimeMillis();

        private void touch() {
            updatedAt = System.currentTimeMillis();
        }
    }
}
