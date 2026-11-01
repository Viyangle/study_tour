package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.aiservice.AttractionTagScorerAi;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.mapper.AttractionTagMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.AttractionTag;
import com.viyangle.study_tour.pojo.TagDimension;
import com.viyangle.study_tour.rag.StudyTourTagClassifier;
import com.viyangle.study_tour.service.TagVectorLabelService;
import com.viyangle.study_tour.service.TagVectorService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 自动打分实现：LLM 输出 15 维 0~1 分数，失败/超时回退到关键词规则。
 *
 * <p>写库策略：只删除 {@code LLM/LLM_V2/RULE} 的旧行，{@code MANUAL} 行保留；
 * 因此人工纠正不会被自动任务覆盖。</p>
 */
@Slf4j
@Service
public class TagVectorLabelServiceImpl implements TagVectorLabelService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double LLM_PRIOR_STRENGTH = 3.0;
    private static final double RULE_PRIOR_STRENGTH = 1.5;
    private static final int MAX_HIGH_DIMENSIONS = 5;

    @Autowired
    private AttractionTagMapper attractionTagMapper;

    @Autowired
    private AttractionMapper attractionMapper;

    @Autowired
    private TagVectorService tagVectorService;

    @Autowired
    private StudyTourTagClassifier classifier;

    @Autowired(required = false)
    private AttractionTagScorerAi scorer;

    @Value("${app.tag-vector.auto-label-enabled:true}")
    private boolean enabled = true;

    @Value("${app.tag-vector.label-timeout-seconds:25}")
    private int timeoutSeconds = 25;

    /** 低于该分数的维度不落库；LLM 常给出 0.1~0.3 的弱相关维度，保留会明显增加噪声。 */
    @Value("${app.tag-vector.min-prior-weight:0.4}")
    private double minPriorWeight = 0.4;

    @Value("${langchain4j.open-ai.chat-model.api-key:}")
    private String llmApiKey;

    private final ExecutorService llmExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "tag-vector-labeler");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    public void shutdown() {
        llmExecutor.shutdownNow();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public int autoLabel(Attraction attraction) {
        int written = labelInternal(attraction, false);
        if (written > 0 && tagVectorService != null) {
            tagVectorService.reload();
        }
        return written;
    }

    @Override
    public int backfillMissing(int limit) {
        if (!enabled || attractionMapper == null) {
            return 0;
        }
        int max = limit <= 0 ? 50 : Math.min(limit, 500);
        List<String> poiIds = attractionMapper.selectPoiIdsWithoutTagVector(max);
        if (poiIds == null || poiIds.isEmpty()) {
            log.info("标签向量补标: 没有待处理景点");
            return 0;
        }

        int labeled = 0;
        for (String poiId : poiIds) {
            try {
                Attraction attraction = attractionMapper.selectByPoiId(poiId);
                if (attraction != null && labelInternal(attraction, true) > 0) {
                    labeled++;
                }
            } catch (Exception e) {
                log.warn("标签向量补标失败: poiId={}, {}", poiId, e.getMessage());
            }
        }
        if (labeled > 0 && tagVectorService != null) {
            tagVectorService.reload();
        }
        log.info("标签向量补标完成: 候选={}, 成功={}", poiIds.size(), labeled);
        return labeled;
    }

    @Override
    public int relabel(String poiId) {
        if (!enabled || poiId == null || poiId.isBlank() || attractionMapper == null) {
            return 0;
        }
        Attraction attraction = attractionMapper.selectByPoiId(poiId);
        if (attraction == null) {
            log.warn("标签向量重算跳过: 景点不存在, poiId={}", poiId);
            return 0;
        }
        int written = labelInternal(attraction, true);
        if (written > 0 && tagVectorService != null) {
            tagVectorService.reload();
        }
        return written;
    }

    private int labelInternal(Attraction attraction, boolean force) {
        if (!enabled || attraction == null || attraction.getPoiId() == null || attraction.getPoiId().isBlank()) {
            return 0;
        }
        List<TagDimension> dimensions = tagVectorService == null ? List.of() : tagVectorService.dimensions();
        if (dimensions.isEmpty()) {
            log.warn("标签向量打分跳过: 维度为空（是否已执行 V4 迁移？）, poiId={}", attraction.getPoiId());
            return 0;
        }

        Map<String, Double> scores = tryLlm(attraction, dimensions);
        if (!scores.isEmpty()) {
            int written = persist(attraction.getPoiId(), dimensions, scores, "LLM_V2", LLM_PRIOR_STRENGTH, force);
            if (written > 0) {
                return written;
            }
        }

        Map<String, Double> ruleScores = ruleScores(attraction, dimensions);
        if (ruleScores.isEmpty()) {
            log.warn("标签向量打分失败: LLM 与规则都没有产出, poiId={}", attraction.getPoiId());
            return 0;
        }
        return persist(attraction.getPoiId(), dimensions, ruleScores, "RULE", RULE_PRIOR_STRENGTH, force);
    }

    private Map<String, Double> tryLlm(Attraction attraction, List<TagDimension> dimensions) {
        if (scorer == null || llmApiKey == null || llmApiKey.isBlank()) {
            return Map.of();
        }
        Future<String> future = null;
        try {
            future = llmExecutor.submit(() -> scorer.score(buildPrompt(attraction)));
            String text = future.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            return parseScores(text, dimensions);
        } catch (Exception e) {
            if (future != null) {
                future.cancel(true);
            }
            log.warn("LLM 标签打分失败，回退规则: poiId={}, {}", attraction.getPoiId(), e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Double> parseScores(String text, List<TagDimension> dimensions) throws Exception {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        String json = text.trim()
                .replaceAll("(?s)^```json\\s*", "")
                .replaceAll("(?s)\\s*```$", "");
        JsonNode root = MAPPER.readTree(json);
        if (root != null && root.has("scores") && root.get("scores").isObject()) {
            root = root.get("scores");
        }
        if (root == null || !root.isObject()) {
            return Map.of();
        }

        Map<String, Double> scores = new LinkedHashMap<>();
        for (TagDimension dimension : dimensions) {
            JsonNode node = root.get(dimension.getCode());
            if ((node == null || node.isNull()) && dimension.getName() != null) {
                node = root.get(dimension.getName());
            }
            if (node != null && node.isNumber()) {
                double value = clamp(node.asDouble(), 0.0, 1.0);
                if (value > 0.0) {
                    scores.put(dimension.getCode(), round(value));
                }
            }
        }

        trimHighDimensions(scores);
        if (scores.values().stream().noneMatch(value -> value > 0.0)) {
            return Map.of();
        }
        return scores;
    }

    private void trimHighDimensions(Map<String, Double> scores) {
        List<Map.Entry<String, Double>> high = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            if (entry.getValue() >= 0.5) {
                high.add(entry);
            }
        }
        if (high.size() <= MAX_HIGH_DIMENSIONS) {
            return;
        }
        high.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        for (int i = MAX_HIGH_DIMENSIONS; i < high.size(); i++) {
            scores.remove(high.get(i).getKey());
        }
    }

    private Map<String, Double> ruleScores(Attraction attraction, List<TagDimension> dimensions) {
        if (classifier == null) {
            return Map.of();
        }
        Map<String, Double> byName = classifier.score(
                attraction.getName(),
                attraction.getType(),
                attraction.getAddress(),
                attraction.getPname(),
                attraction.getCityname(),
                attraction.getAdname());
        if (byName.isEmpty()) {
            return Map.of();
        }

        Map<String, TagDimension> dimensionByName = new LinkedHashMap<>();
        for (TagDimension dimension : dimensions) {
            if (dimension.getName() != null) {
                dimensionByName.put(dimension.getName(), dimension);
            }
        }

        Map<String, Double> byCode = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : byName.entrySet()) {
            TagDimension dimension = dimensionByName.get(entry.getKey());
            if (dimension != null && entry.getValue() != null && entry.getValue() > 0.0) {
                byCode.put(dimension.getCode(), round(entry.getValue()));
            }
        }
        return byCode;
    }

    private int persist(String poiId,
                        List<TagDimension> dimensions,
                        Map<String, Double> scores,
                        String source,
                        double priorStrength,
                        boolean force) {
        Set<Long> manualTagIds = Set.of();
        if (!force) {
            List<AttractionTag> existing = attractionTagMapper.selectByPoiId(poiId);
            manualTagIds = existing == null ? Set.of() : existing.stream()
                    .filter(tag -> tag.getTagId() != null && "MANUAL".equalsIgnoreCase(tag.getSource()))
                    .map(AttractionTag::getTagId)
                    .collect(Collectors.toSet());
        }

        List<Long> dimensionTagIds = dimensions.stream()
                .map(TagDimension::getTagId)
                .filter(tagId -> tagId != null)
                .toList();
        if (!dimensionTagIds.isEmpty()) {
            if (force) {
                attractionTagMapper.deleteWeightsByPoiIdAndTagIds(poiId, dimensionTagIds);
            } else {
                attractionTagMapper.deleteAutoWeightsByPoiIdAndTagIds(poiId, dimensionTagIds);
            }
        }

        int written = 0;
        for (TagDimension dimension : dimensions) {
            Double score = scores.get(dimension.getCode());
            if (score == null || score < minPriorWeight || dimension.getTagId() == null
                    || manualTagIds.contains(dimension.getTagId())) {
                continue;
            }
            try {
                attractionTagMapper.insertWithWeight(poiId, dimension.getTagId(), source, round(score), priorStrength);
                written++;
            } catch (Exception e) {
                log.warn("写入标签权重失败，跳过: poiId={}, code={}, {}", poiId, dimension.getCode(), e.getMessage());
            }
        }
        log.info("景点标签向量打分完成: poiId={}, source={}, dims={}, force={}", poiId, source, written, force);
        return written;
    }

    private String buildPrompt(Attraction attraction) {
        return "名称: " + safe(attraction.getName()) + "\n"
                + "类型: " + safe(attraction.getType()) + "\n"
                + "地址: " + safe(attraction.getAddress()) + "\n"
                + "省市区: " + safe(attraction.getPname()) + " "
                + safe(attraction.getCityname()) + " " + safe(attraction.getAdname()) + "\n"
                + "高德分类: " + safe(attraction.getTypecode());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
