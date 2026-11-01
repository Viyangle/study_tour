package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AttractionTagMapper;
import com.viyangle.study_tour.mapper.TagDimensionMapper;
import com.viyangle.study_tour.pojo.AttractionTag;
import com.viyangle.study_tour.pojo.AttractionTagVector;
import com.viyangle.study_tour.pojo.TagDimension;
import com.viyangle.study_tour.service.TagVectorService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * 标签向量加载与查询实现。
 *
 * <p>权重公式与 docs/attraction-tag-vector-design.md §7.1 对齐：
 * {@code s_i = clamp((κ_i·p_i + pos_i) / (κ_i + pos_i + neg_i), 0, 1)}。
 * 数据库的 {@code effective_weight} 生成列已经算了同一套值，优先直接使用。</p>
 */
@Slf4j
@Service
public class TagVectorServiceImpl implements TagVectorService {

    private static final double DEFAULT_PRIOR_WEIGHT = 1.0;
    private static final double DEFAULT_PRIOR_STRENGTH = 3.0;
    private static final double MIN_VIEW_WEIGHT = 0.05;

    @Autowired
    private TagDimensionMapper tagDimensionMapper;

    @Autowired
    private AttractionTagMapper attractionTagMapper;

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
            for (TagDimension dimension : current) {
                int ordinal = dimension.getOrdinal() - 1;
                if (ordinal < 0 || ordinal >= current.size()) {
                    log.warn("标签维度 ordinal 越界，已忽略: code={}, ordinal={}", dimension.getCode(), dimension.getOrdinal());
                    continue;
                }
                ordinalByTagId.put(dimension.getTagId(), ordinal);
                ordinalByTagName.put(dimension.getName(), ordinal);
            }

            List<AttractionTag> edges = attractionTagMapper.selectAll();
            Map<String, double[]> vectors = new HashMap<>();
            Map<String, double[]> priors = new HashMap<>();
            Map<String, double[]> supports = new HashMap<>();
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
                    double[] vector = vectors.computeIfAbsent(edge.getPoiId(), k -> new double[current.size()]);
                    double[] prior = priors.computeIfAbsent(edge.getPoiId(), k -> new double[current.size()]);
                    double[] support = supports.computeIfAbsent(edge.getPoiId(), k -> new double[current.size()]);

                    double priorWeight = valueOrDefault(edge.getPriorWeight(), DEFAULT_PRIOR_WEIGHT);
                    double priorStrength = valueOrDefault(edge.getPriorStrength(), DEFAULT_PRIOR_STRENGTH);
                    double pos = valueOrDefault(edge.getPosCount(), 0.0);
                    double neg = valueOrDefault(edge.getNegCount(), 0.0);
                    double weight = edge.getEffectiveWeight() != null
                            ? edge.getEffectiveWeight()
                            : heat(priorWeight, priorStrength, pos, neg);

                    vector[ordinal] = Math.max(vector[ordinal], clamp(weight, 0.0, 1.0));
                    prior[ordinal] = Math.max(prior[ordinal], clamp(priorWeight, 0.0, 1.0));
                    support[ordinal] = Math.max(support[ordinal], Math.max(0.0, pos + neg));

                    int version = edge.getVectorVersion() == null ? 1 : edge.getVectorVersion();
                    vectorVersions.merge(edge.getPoiId(), version, Math::max);
                    if (edge.getUpdatedAt() != null) {
                        updatedAt.merge(edge.getPoiId(), edge.getUpdatedAt(), TagVectorServiceImpl::maxTime);
                    }
                }
            }

            snapshot = new Snapshot(
                    true,
                    dimVersion,
                    List.copyOf(current),
                    Collections.unmodifiableMap(ordinalByTagName),
                    Collections.unmodifiableMap(vectors),
                    Collections.unmodifiableMap(priors),
                    Collections.unmodifiableMap(supports),
                    Collections.unmodifiableMap(vectorVersions),
                    Collections.unmodifiableMap(updatedAt));

            log.info("标签向量加载完成: dimVersion={}, 维度={}, 景点={}", dimVersion, current.size(), vectors.size());
        } catch (Exception e) {
            snapshot = Snapshot.empty();
            log.warn("标签向量加载失败，按未启用处理: {}", e.getMessage());
        }
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
        double[] support = snapshot.supports().getOrDefault(poiId, new double[size]);

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

    private static double heat(double prior, double kappa, double pos, double neg) {
        double denominator = kappa + pos + neg;
        if (denominator <= 0.0) {
            return clamp(prior, 0.0, 1.0);
        }
        return clamp((kappa * prior + pos) / denominator, 0.0, 1.0);
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
            List<TagDimension> dimensions,
            Map<String, Integer> ordinalByTagName,
            Map<String, double[]> vectors,
            Map<String, double[]> priors,
            Map<String, double[]> supports,
            Map<String, Integer> vectorVersions,
            Map<String, LocalDateTime> updatedAt) {

        private static Snapshot empty() {
            return new Snapshot(false, 0, List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }
}
