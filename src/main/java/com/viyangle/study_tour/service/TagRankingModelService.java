package com.viyangle.study_tour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.mapper.TagRankingModelMapper;
import com.viyangle.study_tour.pojo.TagRankingModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 加载当前 active 的 pairwise 排序模型；没有模型时返回空系数，由 scorer 用默认权重。
 */
@Slf4j
@Service
public class TagRankingModelService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired(required = false)
    private TagRankingModelMapper rankingModelMapper;

    private volatile Snapshot snapshot = Snapshot.empty();

    @PostConstruct
    void init() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${app.tag-vector.ranking-model-refresh-ms:300000}")
    public void refresh() {
        if (rankingModelMapper == null) {
            snapshot = Snapshot.empty();
            return;
        }
        try {
            TagRankingModel model = rankingModelMapper.selectActive();
            if (model == null || model.getCoefficients() == null || model.getCoefficients().isBlank()) {
                snapshot = Snapshot.empty();
                return;
            }
            Map<String, Double> coefficients = new LinkedHashMap<>();
            JsonNode root = MAPPER.readTree(model.getCoefficients());
            root.fields().forEachRemaining(entry -> {
                if (entry.getValue() != null && entry.getValue().isNumber()) {
                    coefficients.put(entry.getKey(), entry.getValue().asDouble());
                }
            });
            snapshot = new Snapshot(
                    model.getModelVersion(),
                    model.getIntercept() == null ? 0.0 : model.getIntercept(),
                    Collections.unmodifiableMap(coefficients));
            log.info("加载 pairwise 排序模型: version={}, features={}, samples={}",
                    model.getModelVersion(), coefficients.size(), model.getSampleCount());
        } catch (Exception e) {
            log.warn("加载 pairwise 排序模型失败，保留上一次快照: {}", e.getMessage());
        }
    }

    public Map<String, Double> coefficients() {
        return snapshot.coefficients();
    }

    public double intercept() {
        return snapshot.intercept();
    }

    public String modelVersion() {
        return snapshot.modelVersion();
    }

    private record Snapshot(String modelVersion, double intercept, Map<String, Double> coefficients) {
        private static Snapshot empty() {
            return new Snapshot(null, 0.0, Map.of());
        }
    }
}
