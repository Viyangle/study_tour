package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.RouteAttractionMapper;
import com.viyangle.study_tour.mapper.RouteMapper;
import com.viyangle.study_tour.mapper.RouteTagWeightMapper;
import com.viyangle.study_tour.pojo.AttractionTagVector;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.pojo.RouteTagWeight;
import com.viyangle.study_tour.pojo.TagDimension;
import com.viyangle.study_tour.service.RouteTagVectorService;
import com.viyangle.study_tour.service.TagVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路线向量 = 站点景点向量按时长加权平均。
 *
 * <p>例如一条路线 60% 历史 + 40% 科技，两个维度都会保留，不再被强制压成单值标签。</p>
 */
@Slf4j
@Service
public class RouteTagVectorServiceImpl implements RouteTagVectorService {

    private static final double MIN_STORE_WEIGHT = 0.05;
    private static final int DEFAULT_STOP_MINUTES = 60;

    @Autowired
    private RouteTagWeightMapper routeTagWeightMapper;

    @Autowired
    private RouteAttractionMapper routeAttractionMapper;

    @Autowired
    private RouteMapper routeMapper;

    @Autowired
    private TagVectorService tagVectorService;

    /** 模型 tagVector 在最终路线向量中的权重；其余来自站点聚合。 */
    @Value("${app.tag-vector.route-model-weight:0.3}")
    private double routeModelWeight = 0.3;

    @Override
    public String rebuild(Long routeId, List<RouteAttraction> stops) {
        return rebuild(routeId, stops, null);
    }

    @Override
    public String rebuild(Long routeId, List<RouteAttraction> stops, Map<String, Double> modelTagVector) {
        if (routeId == null || tagVectorService == null || !tagVectorService.isReady()) {
            return null;
        }
        List<TagDimension> dimensions = tagVectorService.dimensions();
        if (dimensions.isEmpty()) {
            return null;
        }
        List<RouteAttraction> actualStops = stops == null
                ? routeAttractionMapper.selectByRouteId(routeId)
                : stops;
        double[] derived = aggregate(actualStops, dimensions.size());
        double[] model = modelVector(modelTagVector, dimensions);
        double[] vector = blend(derived, model, dimensions.size());
        if (vector == null) {
            return null;
        }

        routeTagWeightMapper.deleteByRouteId(routeId);
        List<RouteTagWeight> rows = new ArrayList<>();
        for (int i = 0; i < dimensions.size(); i++) {
            TagDimension dimension = dimensions.get(i);
            double weight = clamp(vector[i], 0.0, 1.0);
            if (dimension.getTagId() == null || weight < MIN_STORE_WEIGHT) {
                continue;
            }
            rows.add(new RouteTagWeight(routeId, dimension.getTagId(), round(weight),
                    model == null ? "DERIVED" : "LLM_BLEND", 1, null));
        }
        if (!rows.isEmpty()) {
            routeTagWeightMapper.batchInsert(rows);
        }

        String primary = primaryName(vector, dimensions);
        if (primary != null) {
            routeMapper.updateTag(routeId, primary);
        }
        log.info("路线标签向量重算完成: routeId={}, stops={}, modelDims={}, dims={}, primary={}",
                routeId, actualStops == null ? 0 : actualStops.size(),
                modelTagVector == null ? 0 : modelTagVector.size(), rows.size(), primary);
        return primary;
    }

    private double[] modelVector(Map<String, Double> modelTagVector, List<TagDimension> dimensions) {
        if (modelTagVector == null || modelTagVector.isEmpty()) {
            return null;
        }
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
        double[] vector = new double[dimensions.size()];
        boolean any = false;
        for (Map.Entry<String, Double> entry : modelTagVector.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            String key = entry.getKey().trim();
            Integer ordinal = ordinalByName.get(key);
            if (ordinal == null) {
                ordinal = ordinalByCode.get(key);
            }
            if (ordinal == null) {
                continue;
            }
            vector[ordinal] = Math.max(vector[ordinal], clamp(entry.getValue(), 0.0, 1.0));
            any = true;
        }
        return any ? vector : null;
    }

    private double[] blend(double[] derived, double[] model, int dimension) {
        if (derived == null && model == null) {
            return null;
        }
        if (derived == null) {
            return model;
        }
        if (model == null) {
            return derived;
        }
        double modelWeight = clamp(routeModelWeight, 0.0, 1.0);
        double[] vector = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = clamp(derived[i] * (1.0 - modelWeight) + model[i] * modelWeight, 0.0, 1.0);
        }
        return vector;
    }

    @Override
    public double[] vector(Long routeId) {
        if (routeId == null || tagVectorService == null || !tagVectorService.isReady()) {
            return new double[0];
        }
        List<TagDimension> dimensions = tagVectorService.dimensions();
        List<RouteTagWeight> rows = routeTagWeightMapper.selectByRouteId(routeId);
        if (rows != null && !rows.isEmpty()) {
            double[] vector = new double[dimensions.size()];
            Map<Long, Integer> ordinalByTagId = ordinalByTagId(dimensions);
            for (RouteTagWeight row : rows) {
                Integer ordinal = row.getTagId() == null ? null : ordinalByTagId.get(row.getTagId());
                if (ordinal != null && row.getWeight() != null) {
                    vector[ordinal] = clamp(row.getWeight(), 0.0, 1.0);
                }
            }
            return vector;
        }

        List<RouteAttraction> stops = routeAttractionMapper.selectByRouteId(routeId);
        double[] vector = aggregate(stops, dimensions.size());
        return vector == null ? new double[dimensions.size()] : vector;
    }

    @Override
    public AttractionTagVector view(Long routeId) {
        if (routeId == null || tagVectorService == null || !tagVectorService.isReady()) {
            return null;
        }
        List<TagDimension> dimensions = tagVectorService.dimensions();
        if (dimensions.isEmpty()) {
            return null;
        }

        List<RouteTagWeight> rows = routeTagWeightMapper.selectByRouteId(routeId);
        double[] vector;
        int version = 1;
        LocalDateTime updatedAt = null;
        if (rows != null && !rows.isEmpty()) {
            vector = new double[dimensions.size()];
            Map<Long, Integer> ordinalByTagId = ordinalByTagId(dimensions);
            for (RouteTagWeight row : rows) {
                Integer ordinal = row.getTagId() == null ? null : ordinalByTagId.get(row.getTagId());
                if (ordinal != null && row.getWeight() != null) {
                    vector[ordinal] = clamp(row.getWeight(), 0.0, 1.0);
                }
                if (row.getVectorVersion() != null) {
                    version = Math.max(version, row.getVectorVersion());
                }
                if (row.getUpdatedAt() != null) {
                    updatedAt = updatedAt == null || row.getUpdatedAt().isAfter(updatedAt)
                            ? row.getUpdatedAt() : updatedAt;
                }
            }
        } else {
            vector = aggregate(loadStops(routeId), dimensions.size());
            if (vector == null) {
                return null;
            }
        }

        return buildView(vector, dimensions, version, updatedAt);
    }

    private List<RouteAttraction> loadStops(Long routeId) {
        return routeAttractionMapper.selectByRouteId(routeId);
    }

    private double[] aggregate(List<RouteAttraction> stops, int dimension) {
        if (stops == null || stops.isEmpty() || dimension <= 0) {
            return null;
        }
        double[] sum = new double[dimension];
        double totalWeight = 0.0;
        for (RouteAttraction stop : stops) {
            if (stop == null || stop.getPoiId() == null || stop.getPoiId().isBlank()) {
                continue;
            }
            double[] vector = tagVectorService.vector(stop.getPoiId());
            if (vector.length == 0) {
                continue;
            }
            boolean hasSignal = false;
            for (double value : vector) {
                if (value > 0) {
                    hasSignal = true;
                    break;
                }
            }
            if (!hasSignal) {
                continue;
            }
            int minutes = stop.getRecommendedDuration() != null && stop.getRecommendedDuration() > 0
                    ? stop.getRecommendedDuration()
                    : DEFAULT_STOP_MINUTES;
            int size = Math.min(dimension, vector.length);
            for (int i = 0; i < size; i++) {
                sum[i] += vector[i] * minutes;
            }
            totalWeight += minutes;
        }
        if (totalWeight <= 0) {
            return null;
        }
        double[] vector = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = clamp(sum[i] / totalWeight, 0.0, 1.0);
        }
        return vector;
    }

    private AttractionTagVector buildView(double[] vector,
                                          List<TagDimension> dimensions,
                                          int version,
                                          LocalDateTime updatedAt) {
        double total = 0.0;
        for (double value : vector) {
            total += value;
        }

        List<Double> weights = new ArrayList<>(dimensions.size());
        List<Double> share = new ArrayList<>(dimensions.size());
        List<AttractionTagVector.Dim> dims = new ArrayList<>();
        int primaryIndex = -1;
        double primaryWeight = -1.0;
        for (int i = 0; i < dimensions.size(); i++) {
            double weight = clamp(vector[i], 0.0, 1.0);
            weights.add(round(weight));
            share.add(total > 0 ? round(weight / total) : 0.0);
            if (weight > primaryWeight) {
                primaryWeight = weight;
                primaryIndex = i;
            }
            if (weight >= MIN_STORE_WEIGHT) {
                TagDimension dimension = dimensions.get(i);
                dims.add(new AttractionTagVector.Dim(
                        dimension.getCode(),
                        dimension.getName(),
                        round(weight),
                        0.0,
                        0.0));
            }
        }
        dims.sort(Comparator.comparingDouble(AttractionTagVector.Dim::getWeight).reversed());

        String primary = null;
        String primaryName = null;
        if (primaryIndex >= 0 && primaryWeight > 0) {
            TagDimension dimension = dimensions.get(primaryIndex);
            primary = dimension.getCode();
            primaryName = dimension.getName();
        }
        return new AttractionTagVector(
                dimensions.get(0).getDimVersion(),
                version,
                updatedAt,
                primary,
                primaryName,
                weights,
                share,
                dims);
    }

    private String primaryName(double[] vector, List<TagDimension> dimensions) {
        int index = -1;
        double max = -1.0;
        for (int i = 0; i < dimensions.size(); i++) {
            if (vector[i] > max) {
                max = vector[i];
                index = i;
            }
        }
        return index >= 0 && max > 0 ? dimensions.get(index).getName() : null;
    }

    private Map<Long, Integer> ordinalByTagId(List<TagDimension> dimensions) {
        Map<Long, Integer> result = new HashMap<>();
        for (int i = 0; i < dimensions.size(); i++) {
            if (dimensions.get(i).getTagId() != null) {
                result.put(dimensions.get(i).getTagId(), i);
            }
        }
        return result;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
