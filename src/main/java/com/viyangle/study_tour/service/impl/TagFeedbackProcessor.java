package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.AttractionTagMapper;
import com.viyangle.study_tour.mapper.ProjectMapper;
import com.viyangle.study_tour.mapper.RouteAttractionMapper;
import com.viyangle.study_tour.mapper.TagFeedbackEventMapper;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.AttractionTag;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.pojo.TagDimension;
import com.viyangle.study_tour.pojo.TagFeedbackEvent;
import com.viyangle.study_tour.service.TagFeedbackEventType;
import com.viyangle.study_tour.service.TagVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 反馈事件处理器：把 PENDING 事件转成 {@code pos/neg} 证据，并按热度公式更新权重。
 *
 * <p>每批处理一次，成功后刷新 {@link TagVectorService}；单条失败最多重试 3 次。
 * 单用户单景点 30 天证据上限只对直接挂在 poi_id 上的事件生效，路线/项目事件按站点展开，
 * 不逐一做上限查询，避免一次事件产生几十次 SQL。</p>
 */
@Slf4j
@Service
public class TagFeedbackProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double MIN_CREDIT = 0.001;
    private static final double CREDIT_EPSILON = 0.05;
    private static final int MAX_ATTEMPTS = 3;

    @Autowired
    private TagFeedbackEventMapper feedbackEventMapper;

    @Autowired
    private AttractionTagMapper attractionTagMapper;

    @Autowired
    private RouteAttractionMapper routeAttractionMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private AccountTagPrefMapper accountTagPrefMapper;

    @Autowired
    private TagVectorService tagVectorService;

    @Value("${app.tag-vector.feedback-enabled:true}")
    private boolean enabled = true;

    /** 是否真的把事件应用到景点权重；默认只采集，不自动改库。 */
    @Value("${app.tag-vector.apply-enabled:false}")
    private boolean applyEnabled = false;

    @Value("${app.tag-vector.apply-batch-size:100}")
    private int batchSize = 100;

    @Value("${app.tag-vector.pos-half-life-days:90}")
    private double posHalfLifeDays = 90.0;

    @Value("${app.tag-vector.neg-half-life-days:180}")
    private double negHalfLifeDays = 180.0;

    @Value("${app.tag-vector.user-cap-30d:0.8}")
    private double userCap30d = 0.8;

    @Scheduled(fixedDelayString = "${app.tag-vector.apply-interval-ms:30000}")
    public void processPending() {
        if (!enabled || !applyEnabled || tagVectorService == null || !tagVectorService.isReady()) {
            return;
        }
        List<TagFeedbackEvent> events = feedbackEventMapper.selectPending(batchSize);
        if (events == null || events.isEmpty()) {
            return;
        }

        int applied = 0;
        for (TagFeedbackEvent event : events) {
            try {
                ApplyResult result = apply(event);
                if (result.applied()) {
                    feedbackEventMapper.markApplied(event.getId());
                    applied++;
                } else {
                    feedbackEventMapper.markIgnored(event.getId(), result.note());
                }
            } catch (Exception e) {
                int attempts = (event.getAttempts() == null ? 0 : event.getAttempts()) + 1;
                log.warn("反馈事件处理失败: id={}, type={}, attempts={}, {}",
                        event.getId(), event.getEventType(), attempts, e.getMessage());
                if (attempts >= MAX_ATTEMPTS) {
                    feedbackEventMapper.markIgnored(event.getId(), "failed: " + e.getMessage());
                } else {
                    feedbackEventMapper.incrementAttempts(event.getId());
                }
            }
        }

        if (applied > 0) {
            tagVectorService.reload();
            log.info("反馈事件处理完成: applied={}, batch={}", applied, events.size());
        }
    }

    private ApplyResult apply(TagFeedbackEvent event) {
        TagFeedbackEventType type = TagFeedbackEventType.from(event.getEventType());
        if (type == null) {
            return new ApplyResult(false, "unknown event type");
        }
        if (!type.affectsAttractionTags()) {
            // 点赞/点踩/收藏/加入项目/评价只代表用户偏好，不改变景点-标签属性
            return new ApplyResult(false, "preference-only");
        }

        double value = event.getEventValue() == null ? 0.0 : event.getEventValue();
        if (Math.abs(value) < 1e-9) {
            return new ApplyResult(true, "no-op");
        }

        Set<String> targets = resolveTargets(event);
        if (targets.isEmpty()) {
            return new ApplyResult(false, "no target attraction");
        }

        List<TagDimension> dimensions = tagVectorService.dimensions();
        if (dimensions.isEmpty()) {
            return new ApplyResult(false, "tag dimensions unavailable");
        }

        double[] intent = resolveIntent(event, dimensions);
        Integer explicitOrdinal = explicitOrdinal(event, dimensions);

        boolean applied = false;
        boolean capHit = false;
        for (String poiId : targets) {
            double targetValue = value;
            if (event.getAccountId() != null) {
                double used = feedbackEventMapper.sumAppliedWeightByAccountAndPoiSince(
                        event.getAccountId(), poiId, LocalDateTime.now().minusDays(30));
                double remaining = userCap30d - used;
                if (remaining <= 1e-9) {
                    capHit = true;
                    continue;
                }
                targetValue = value > 0
                        ? Math.min(value, remaining)
                        : -Math.min(-value, remaining);
            }
            if (Math.abs(targetValue) < 1e-9) {
                continue;
            }

            double[] vector = tagVectorService.vector(poiId);
            double[] credit = credit(vector, intent, dimensions.size(), explicitOrdinal);
            Map<Long, AttractionTag> rows = loadRows(poiId);
            LocalDateTime now = LocalDateTime.now();

            for (int i = 0; i < dimensions.size(); i++) {
                if (credit[i] < MIN_CREDIT) {
                    continue;
                }
                TagDimension dimension = dimensions.get(i);
                if (dimension.getTagId() == null) {
                    continue;
                }

                AttractionTag row = rows.get(dimension.getTagId());
                double pos = row == null || row.getPosCount() == null ? 0.0 : row.getPosCount();
                double neg = row == null || row.getNegCount() == null ? 0.0 : row.getNegCount();
                if (row != null && row.getLastDecayAt() != null) {
                    pos = decay(pos, row.getLastDecayAt(), now, posHalfLifeDays);
                    neg = decay(neg, row.getLastDecayAt(), now, negHalfLifeDays);
                }

                double delta = Math.abs(targetValue) * credit[i];
                if (targetValue > 0) {
                    pos += delta;
                } else {
                    neg += delta;
                }
                attractionTagMapper.upsertEvidence(poiId, dimension.getTagId(), pos, neg);
                applied = true;
            }
        }
        if (applied) {
            return new ApplyResult(true, "applied");
        }
        return new ApplyResult(false, capHit ? "user cap reached" : "no evidence");
    }

    private Map<Long, AttractionTag> loadRows(String poiId) {
        List<AttractionTag> rows = attractionTagMapper.selectByPoiId(poiId);
        Map<Long, AttractionTag> result = new HashMap<>();
        if (rows != null) {
            for (AttractionTag row : rows) {
                if (row != null && row.getTagId() != null) {
                    result.put(row.getTagId(), row);
                }
            }
        }
        return result;
    }

    private Set<String> resolveTargets(TagFeedbackEvent event) {
        Set<String> targets = new LinkedHashSet<>();
        if (event.getPoiId() != null && !event.getPoiId().isBlank()) {
            targets.add(event.getPoiId());
        }
        if (event.getRouteId() != null) {
            addRouteStops(event.getRouteId(), targets);
        }
        if (event.getProjectId() != null) {
            Project project = projectMapper.selectById(event.getProjectId());
            if (project != null && project.getRouteId() != null) {
                addRouteStops(project.getRouteId(), targets);
            }
        }
        return targets;
    }

    private void addRouteStops(Long routeId, Set<String> targets) {
        List<RouteAttraction> stops = routeAttractionMapper.selectByRouteId(routeId);
        if (stops != null) {
            for (RouteAttraction stop : stops) {
                if (stop != null && stop.getPoiId() != null && !stop.getPoiId().isBlank()) {
                    targets.add(stop.getPoiId());
                }
            }
        }
    }

    private double[] resolveIntent(TagFeedbackEvent event, List<TagDimension> dimensions) {
        double[] intent = new double[dimensions.size()];
        if (event.getIntentVector() != null && !event.getIntentVector().isBlank()) {
            try {
                JsonNode root = MAPPER.readTree(event.getIntentVector());
                if (root != null && root.isArray()) {
                    int size = Math.min(intent.length, root.size());
                    for (int i = 0; i < size; i++) {
                        intent[i] = clamp(root.get(i).asDouble(0.0), 0.0, 1.0);
                    }
                    return intent;
                }
            } catch (Exception e) {
                log.debug("忽略无法解析的 intent_vector: eventId={}", event.getId());
            }
        }

        if (event.getAccountId() == null) {
            return intent;
        }
        if (accountTagPrefMapper == null) {
            return intent;
        }
        List<AccountTagPref> prefs = accountTagPrefMapper.selectByAccountId(event.getAccountId());
        if (prefs == null || prefs.isEmpty()) {
            return intent;
        }
        Map<Long, Integer> ordinalByTagId = new HashMap<>();
        for (int i = 0; i < dimensions.size(); i++) {
            if (dimensions.get(i).getTagId() != null) {
                ordinalByTagId.put(dimensions.get(i).getTagId(), i);
            }
        }
        for (AccountTagPref pref : prefs) {
            Integer ordinal = pref == null || pref.getTagId() == null ? null : ordinalByTagId.get(pref.getTagId());
            if (ordinal != null) {
                intent[ordinal] = 1.0;
            }
        }
        return intent;
    }

    private Integer explicitOrdinal(TagFeedbackEvent event, List<TagDimension> dimensions) {
        if (event.getTagId() == null) {
            return null;
        }
        for (int i = 0; i < dimensions.size(); i++) {
            if (event.getTagId().equals(dimensions.get(i).getTagId())) {
                return i;
            }
        }
        return null;
    }

    /**
     * 信用分配：按「用户意图 × 景点当前向量」分摊；没有意图/向量时退化到意图或均匀；
     * 显式纠正只给指定维度。
     */
    private double[] credit(double[] vector, double[] intent, int dimension, Integer explicitOrdinal) {
        double[] credit = new double[dimension];
        if (explicitOrdinal != null && explicitOrdinal >= 0 && explicitOrdinal < dimension) {
            credit[explicitOrdinal] = 1.0;
            return credit;
        }

        double sum = 0.0;
        for (int i = 0; i < dimension; i++) {
            double vectorValue = i < vector.length ? vector[i] : 0.0;
            double intentValue = i < intent.length ? intent[i] : 0.0;
            credit[i] = (CREDIT_EPSILON + intentValue) * vectorValue;
            sum += credit[i];
        }
        if (sum <= 1e-9) {
            for (int i = 0; i < dimension; i++) {
                credit[i] = i < intent.length ? intent[i] : 0.0;
                sum += credit[i];
            }
        }
        if (sum <= 1e-9) {
            Arrays.fill(credit, 1.0 / dimension);
            return credit;
        }
        for (int i = 0; i < dimension; i++) {
            credit[i] /= sum;
        }
        return credit;
    }

    private double decay(double count, LocalDateTime last, LocalDateTime now, double halfLifeDays) {
        if (count <= 0 || last == null || halfLifeDays <= 0) {
            return Math.max(0.0, count);
        }
        double days = Duration.between(last, now).toMillis() / 86_400_000.0;
        if (days <= 0) {
            return count;
        }
        return count * Math.pow(0.5, days / halfLifeDays);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ApplyResult(boolean applied, String note) {
    }
}
