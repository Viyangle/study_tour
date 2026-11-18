package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AttractionTagLearnedMapper;
import com.viyangle.study_tour.mapper.AttractionTagMapper;
import com.viyangle.study_tour.mapper.TagDimensionMapper;
import com.viyangle.study_tour.pojo.AttractionTag;
import com.viyangle.study_tour.pojo.AttractionTagLearned;
import com.viyangle.study_tour.pojo.AttractionTagVector;
import com.viyangle.study_tour.pojo.TagDimension;
import com.viyangle.study_tour.service.TagVectorService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 标签向量加载与查询实现。
 *
 * <p>反馈 2.0 起向量由两层组成：{@code attraction_tag.prior_weight} 的语义先验，
 * 以及 {@code attraction_tag_learned} 的全局学习层。服务时按 {@code serve-mode}
 * 合成：PRIOR_ONLY 只用先验；BLEND/PERSONALIZED 用
 * {@code s = (1-w)p + w·h}，{@code w = min(maxWeight, distinct_users/(distinct_users+κ))}；
 * SHADOW 线上仍用先验，但保留 shadow 向量供反事实评估。</p>
 */
@Slf4j
@Service
public class TagVectorServiceImpl implements TagVectorService {

    private static final double DEFAULT_PRIOR_WEIGHT = 1.0;
    private static final double MIN_VIEW_WEIGHT = 0.05;

    @Autowired
    private TagDimensionMapper tagDimensionMapper;

    @Autowired
    private AttractionTagMapper attractionTagMapper;

    /** V6 之前/单元测试可能没有学习层 Mapper；缺失时按 PRIOR_ONLY 处理。 */
    @Autowired(required = false)
    private AttractionTagLearnedMapper learnedMapper;

    @Value("${app.tag-vector.serve-mode:PRIOR_ONLY}")
    private String serveMode = "PRIOR_ONLY";

    @Value("${app.tag-vector.learned-max-weight:0.3}")
    private double learnedMaxWeight = 0.3;

    @Value("${app.tag-vector.support-shrink-kappa:5.0}")
    private double supportShrinkKappa = 5.0;

    @Value("${app.tag-vector.learned-prior-pseudo-count:3.0}")
    private double learnedPriorPseudoCount = 3.0;

    private volatile Snapshot snapshot = Snapshot.empty();

    @PostConstruct
    @Override
    public void reload() {
        try {
            List<TagDimension> all = tagDimensionMapper.selectAll();
            if (all == null || all.isEmpty()) {
                snapshot = Snapshot.empty();
                log.warn("标签向量未加载：tag_dimension 为空（是否已执行 V4 迁移？）");
                return;
            }

            int dimVersion = all.stream()
                    .map(TagDimension::getDimVersion)
                    .filter(Objects::nonNull)
                    .max(Integer::compareTo)
                    .orElse(1);

            List<TagDimension> current = new ArrayList<>();
            for (TagDimension dimension : all) {
                if (dimension == null
                        || !Objects.equals(dimVersion, dimension.getDimVersion())
                        || Boolean.FALSE.equals(dimension.getEnabled())
                        || dimension.getTagId() == null
                        || dimension.getOrdinal() == null
                        || dimension.getName() == null) {
                    continue;
                }
                current.add(dimension);
            }
            if (current.isEmpty()) {
                snapshot = Snapshot.empty();
                log.warn("标签向量未加载：维度版本 {} 没有启用的维度", dimVersion);
                return;
            }
            current.sort(Comparator.comparingInt(TagDimension::getOrdinal));

            Map<Long, Integer> ordinalByTagId = new HashMap<>();
            Map<String, Integer> ordinalByTagName = new HashMap<>();
            for (int ordinal = 0; ordinal < current.size(); ordinal++) {
                TagDimension dimension = current.get(ordinal);
                // 用「启用维度的排序位置」做下标，而不是 ordinal-1：
                // 中间禁用一维后 ordinal 会出现空洞，直接用 ordinal 会导致错位/丢维。
                ordinalByTagId.put(dimension.getTagId(), ordinal);
                ordinalByTagName.put(dimension.getName(), ordinal);
            }

            List<AttractionTag> edges = attractionTagMapper.selectAll();
            Map<String, double[]> priors = new HashMap<>();
            Map<String, double[]> priorEvidence = new HashMap<>();
            Map<String, Integer> vectorVersions = new HashMap<>();
            Map<String, LocalDateTime> updatedAt = new HashMap<>();

            if (edges != null) {
                for (AttractionTag edge : edges) {
                    if (edge == null || edge.getPoiId() == null || edge.getTagId() == null) {
                        continue;
                    }
                    Integer ordinal = ordinalByTagId.get(edge.getTagId());
                    if (ordinal == null) {
                        continue;
                    }
                    double[] prior = priors.computeIfAbsent(edge.getPoiId(), k -> new double[current.size()]);
                    double[] evidence = priorEvidence.computeIfAbsent(edge.getPoiId(), k -> new double[current.size()]);

                    double priorWeight = valueOrDefault(edge.getPriorWeight(), DEFAULT_PRIOR_WEIGHT);
                    double pos = valueOrDefault(edge.getPosCount(), 0.0);
                    double neg = valueOrDefault(edge.getNegCount(), 0.0);
                    // 语义先验只认 prior_weight；pos/neg 是 V4/V5 的历史证据，仅用于兼容展示。
                    prior[ordinal] = Math.max(prior[ordinal], clamp(priorWeight, 0.0, 1.0));
                    evidence[ordinal] = Math.max(evidence[ordinal], Math.max(0.0, pos + neg));

                    int version = edge.getVectorVersion() == null ? 1 : edge.getVectorVersion();
                    vectorVersions.merge(edge.getPoiId(), version, Math::max);
                    if (edge.getUpdatedAt() != null) {
                        updatedAt.merge(edge.getPoiId(), edge.getUpdatedAt(), TagVectorServiceImpl::maxTime);
                    }
                }
            }

            Map<String, double[]> learned = new HashMap<>();
            Map<String, double[]> learnedSupport = new HashMap<>();
            if (learnedMapper != null) {
                try {
                    List<AttractionTagLearned> learnedRows = learnedMapper.selectAll();
                    if (learnedRows != null) {
                        for (AttractionTagLearned row : learnedRows) {
                            if (row == null || row.getPoiId() == null || row.getTagId() == null) {
                                continue;
                            }
                            Integer ordinal = ordinalByTagId.get(row.getTagId());
                            if (ordinal == null) {
                                continue;
                            }
                            double[] prior = priors.getOrDefault(row.getPoiId(), new double[current.size()]);
                            double[] vector = learned.computeIfAbsent(row.getPoiId(), k -> new double[current.size()]);
                            double[] support = learnedSupport.computeIfAbsent(row.getPoiId(), k -> new double[current.size()]);
                            double pos = valueOrDefault(row.getPosCount(), 0.0);
                            double neg = valueOrDefault(row.getNegCount(), 0.0);
                            double priorWeight = clamp(prior[ordinal], 0.0, 1.0);
                            double value = (pos + learnedPriorPseudoCount * priorWeight)
                                    / (pos + neg + learnedPriorPseudoCount);
                            vector[ordinal] = Math.max(vector[ordinal], clamp(value, 0.0, 1.0));
                            support[ordinal] = Math.max(support[ordinal],
                                    row.getDistinctUsers() == null ? 0.0 : Math.max(0, row.getDistinctUsers()));
                            int learnedVersion = row.getVectorVersion() == null ? 1 : row.getVectorVersion();
                            vectorVersions.merge(row.getPoiId(), learnedVersion, Math::max);
                            if (row.getUpdatedAt() != null) {
                                updatedAt.merge(row.getPoiId(), row.getUpdatedAt(), TagVectorServiceImpl::maxTime);
                            }
                        }
                    }
                } catch (Exception e) {
                    // V6 尚未执行时仍然要让先验向量可用。
                    log.warn("学习层加载失败，按 PRIOR_ONLY 处理: {}", e.getMessage());
                }
            }

            Set<String> allPoiIds = new java.util.LinkedHashSet<>();
            allPoiIds.addAll(priors.keySet());
            allPoiIds.addAll(learned.keySet());
            Map<String, double[]> serveVectors = new HashMap<>();
            Map<String, double[]> shadowVectors = new HashMap<>();
            String mode = normalizeServeMode(serveMode);
            for (String poiId : allPoiIds) {
                double[] prior = priors.getOrDefault(poiId, new double[current.size()]);
                double[] learnedVector = learned.getOrDefault(poiId, new double[current.size()]);
                double[] support = learnedSupport.getOrDefault(poiId, new double[current.size()]);
                double[] serve = new double[current.size()];
                double[] shadow = new double[current.size()];
                for (int i = 0; i < current.size(); i++) {
                    double blended = blend(prior[i], learnedVector[i], support[i]);
                    shadow[i] = blended;
                    serve[i] = switch (mode) {
                        case "BLEND", "PERSONALIZED" -> blended;
                        default -> prior[i];
                    };
                }
                serveVectors.put(poiId, serve);
                shadowVectors.put(poiId, shadow);
            }

            snapshot = new Snapshot(
                    true,
                    dimVersion,
                    mode,
                    List.copyOf(current),
                    Collections.unmodifiableMap(ordinalByTagName),
                    Collections.unmodifiableMap(serveVectors),
                    Collections.unmodifiableMap(shadowVectors),
                    Collections.unmodifiableMap(priors),
                    Collections.unmodifiableMap(learned),
                    Collections.unmodifiableMap(learnedSupport),
                    Collections.unmodifiableMap(priorEvidence),
                    Collections.unmodifiableMap(vectorVersions),
                    Collections.unmodifiableMap(updatedAt));

            log.info("标签向量加载完成: dimVersion={}, 维度={}, 景点={}, serveMode={}",
                    dimVersion, current.size(), allPoiIds.size(), mode);
        } catch (Exception e) {
            // 保留上一次成功快照：DB 抖动时宁可短暂用旧向量，也不要把整个向量能力清空。
            log.error("标签向量加载失败，保留上一次快照: {}", e.getMessage(), e);
        }
    }

    /**
     * 定时刷新，兜底「事件已提交但进程在 reload 前退出」以及 reload 失败后的自愈。
     */
    @Scheduled(
            fixedDelayString = "${app.tag-vector.refresh-interval-ms:300000}",
            initialDelayString = "${app.tag-vector.refresh-interval-ms:300000}")
    public void scheduledReload() {
        reload();
    }

    @Override
    public boolean isReady() {
        return snapshot.ready();
    }

    @Override
    public int dimVersion() {
        return snapshot.dimVersion();
    }

    @Override
    public List<TagDimension> dimensions() {
        return snapshot.dimensions();
    }

    @Override
    public double[] vector(String poiId) {
        double[] vector = poiId == null ? null : snapshot.vectors().get(poiId);
        int size = snapshot.dimensions().size();
        if (vector == null) {
            return new double[size];
        }
        return vector.clone();
    }

    @Override
    public double[] priorVector(String poiId) {
        return copy(snapshot.priors().get(poiId), snapshot.dimensions().size());
    }

    @Override
    public double[] learnedVector(String poiId) {
        return copy(snapshot.learned().get(poiId), snapshot.dimensions().size());
    }

    @Override
    public double[] shadowVector(String poiId) {
        return copy(snapshot.shadowVectors().get(poiId), snapshot.dimensions().size());
    }

    @Override
    public String serveMode() {
        return snapshot.serveMode();
    }

    @Override
    public double[] learnedSupport(String poiId) {
        return copy(snapshot.learnedSupport().get(poiId), snapshot.dimensions().size());
    }

    @Override
    public double[] intentVector(Collection<String> tagNames) {
        int size = snapshot.dimensions().size();
        double[] intent = new double[size];
        if (tagNames == null || tagNames.isEmpty() || size == 0) {
            return intent;
        }
        for (String tagName : tagNames) {
            if (tagName == null || tagName.isBlank()) {
                continue;
            }
            Integer ordinal = snapshot.ordinalByTagName().get(tagName.trim());
            if (ordinal != null && ordinal >= 0 && ordinal < size) {
                intent[ordinal] = 1.0;
            }
        }
        return intent;
    }

    @Override
    public double match(String poiId, double[] intent) {
        if (poiId == null || intent == null || intent.length == 0) {
            return 0.0;
        }
        double[] vector = snapshot.vectors().get(poiId);
        if (vector == null) {
            return 0.0;
        }
        int size = Math.min(intent.length, vector.length);
        double sumIntent = 0.0;
        double dot = 0.0;
        for (int i = 0; i < size; i++) {
            sumIntent += intent[i];
            dot += intent[i] * vector[i];
        }
        if (sumIntent <= 0.0) {
            return 0.0;
        }
        return clamp(dot / sumIntent, 0.0, 1.0);
    }

    @Override
    public double score(String poiId, double[] intent) {
        if (poiId == null || intent == null || intent.length == 0) {
            return 0.0;
        }
        double[] vector = snapshot.vectors().get(poiId);
        if (vector == null) {
            return 0.0;
        }
        int size = Math.min(intent.length, vector.length);
        double dot = 0.0;
        for (int i = 0; i < size; i++) {
            dot += intent[i] * vector[i];
        }
        return Math.max(0.0, dot);
    }

    @Override
    public double similarity(String poiIdA, String poiIdB) {
        if (poiIdA == null || poiIdB == null) {
            return 0.0;
        }
        double[] a = snapshot.vectors().get(poiIdA);
        double[] b = snapshot.vectors().get(poiIdB);
        if (a == null || b == null) {
            return 0.0;
        }
        int size = Math.min(a.length, b.length);
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < size; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA <= 0.0 || normB <= 0.0) {
            return 0.0;
        }
        return clamp(dot / (Math.sqrt(normA) * Math.sqrt(normB)), 0.0, 1.0);
    }

    @Override
    public AttractionTagVector view(String poiId) {
        double[] vector = poiId == null ? null : snapshot.vectors().get(poiId);
        if (vector == null || vector.length == 0) {
            return null;
        }
        List<TagDimension> dimensions = snapshot.dimensions();
        int size = dimensions.size();
        double[] prior = snapshot.priors().getOrDefault(poiId, new double[size]);
        double[] learned = snapshot.learned().getOrDefault(poiId, new double[size]);
        double[] support = snapshot.learnedSupport().getOrDefault(poiId, new double[size]);

        double total = 0.0;
        for (double value : vector) {
            total += value;
        }

        List<Double> weights = new ArrayList<>(size);
        List<Double> share = new ArrayList<>(size);
        List<AttractionTagVector.Dim> dims = new ArrayList<>();
        int primaryIndex = -1;
        double primaryWeight = -1.0;

        for (int i = 0; i < size; i++) {
            double weight = clamp(vector[i], 0.0, 1.0);
            weights.add(round(weight));
            share.add(total > 0 ? round(weight / total) : 0.0);
            if (weight > primaryWeight) {
                primaryWeight = weight;
                primaryIndex = i;
            }
            if (weight >= MIN_VIEW_WEIGHT) {
                TagDimension dimension = dimensions.get(i);
                dims.add(new AttractionTagVector.Dim(
                        dimension.getCode(),
                        dimension.getName(),
                        round(weight),
                        round(clamp(i < prior.length ? prior[i] : 0.0, 0.0, 1.0)),
                        round(clamp(i < learned.length ? learned[i] : 0.0, 0.0, 1.0)),
                        round(Math.max(0.0, i < support.length ? support[i] : 0.0))));
            }
        }
        dims.sort(Comparator.comparingDouble(AttractionTagVector.Dim::getWeight).reversed());

        String primary = null;
        String primaryName = null;
        if (primaryIndex >= 0 && primaryWeight > 0.0) {
            TagDimension dimension = dimensions.get(primaryIndex);
            primary = dimension.getCode();
            primaryName = dimension.getName();
        }

        return new AttractionTagVector(
                snapshot.dimVersion(),
                snapshot.vectorVersions().getOrDefault(poiId, 1),
                snapshot.updatedAt().get(poiId),
                primary,
                primaryName,
                weights,
                share,
                dims);
    }

    private double blend(double prior, double learned, double support) {
        if (support <= 0.0) {
            return clamp(prior, 0.0, 1.0);
        }
        double weight = support / (support + Math.max(0.0, supportShrinkKappa));
        weight = Math.min(Math.max(0.0, learnedMaxWeight), weight);
        return clamp((1.0 - weight) * prior + weight * learned, 0.0, 1.0);
    }

    private static String normalizeServeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "PRIOR_ONLY";
        }
        String normalized = mode.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "SHADOW", "BLEND", "PERSONALIZED", "PRIOR_ONLY" -> normalized;
            default -> "PRIOR_ONLY";
        };
    }

    private static double[] copy(double[] source, int size) {
        if (source == null || size <= 0) {
            return new double[Math.max(0, size)];
        }
        double[] target = new double[size];
        System.arraycopy(source, 0, target, 0, Math.min(size, source.length));
        return target;
    }

    private static double valueOrDefault(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static LocalDateTime maxTime(LocalDateTime left, LocalDateTime right) {
        return left.isAfter(right) ? left : right;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 接口展示保留 4 位小数，避免 JSON 里出现 0.6964285714 这类长尾。 */
    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private record Snapshot(
            boolean ready,
            int dimVersion,
            String serveMode,
            List<TagDimension> dimensions,
            Map<String, Integer> ordinalByTagName,
            Map<String, double[]> vectors,
            Map<String, double[]> shadowVectors,
            Map<String, double[]> priors,
            Map<String, double[]> learned,
            Map<String, double[]> learnedSupport,
            Map<String, double[]> priorEvidence,
            Map<String, Integer> vectorVersions,
            Map<String, LocalDateTime> updatedAt) {

        private static Snapshot empty() {
            return new Snapshot(false, 0, "PRIOR_ONLY", List.of(), Map.of(),
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }
}
