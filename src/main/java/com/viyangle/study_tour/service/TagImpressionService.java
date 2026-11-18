package com.viyangle.study_tour.service;

import com.viyangle.study_tour.mapper.TagImpressionMapper;
import com.viyangle.study_tour.pojo.TagImpression;
import com.viyangle.study_tour.pojo.TagPopularity;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 曝光日志与热度快照。
 *
 * <p>曝光用于区分「没选」和「没看到」，也是 popularity 特征的数据来源。
 * 写入失败不能影响主流程，因此这里只记日志、不抛异常。</p>
 */
@Slf4j
@Service
public class TagImpressionService {

    private static final int MAX_IMPRESSIONS_PER_CALL = 50;
    private static final int DEFAULT_POPULARITY_DAYS = 30;

    @Autowired
    private TagImpressionMapper impressionMapper;

    @Value("${app.tag-vector.popularity-window-days:30}")
    private int popularityWindowDays = DEFAULT_POPULARITY_DAYS;

    @Value("${app.tag-vector.impression-retention-days:180}")
    private int impressionRetentionDays = 180;

    private volatile Map<String, Double> popularity = Map.of();

    @PostConstruct
    void init() {
        refreshPopularity();
    }

    /**
     * 记录一次展示；position 从 1 开始，按传入顺序编号。
     */
    public void record(Long accountId,
                       String sessionKey,
                       String scene,
                       Collection<String> poiIds,
                       String modelVersion) {
        if (poiIds == null || poiIds.isEmpty()) {
            return;
        }
        try {
            Set<String> unique = new LinkedHashSet<>();
            for (String poiId : poiIds) {
                if (poiId != null && !poiId.isBlank()) {
                    unique.add(poiId.trim());
                }
                if (unique.size() >= MAX_IMPRESSIONS_PER_CALL) {
                    break;
                }
            }
            if (unique.isEmpty()) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            List<TagImpression> rows = new ArrayList<>(unique.size());
            int position = 1;
            for (String poiId : unique) {
                rows.add(new TagImpression(null, accountId, sessionKey,
                        scene == null || scene.isBlank() ? "RECOMMEND" : scene,
                        poiId, position++, modelVersion, now));
            }
            impressionMapper.batchInsert(rows);
        } catch (Exception e) {
            log.warn("记录曝光失败: accountId={}, sessionKey={}, {}", accountId, sessionKey, e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.tag-vector.impression-cleanup-interval-ms:86400000}")
    public void cleanup() {
        if (impressionRetentionDays <= 0) {
            return;
        }
        try {
            impressionMapper.deleteBefore(LocalDateTime.now().minusDays(impressionRetentionDays));
        } catch (Exception e) {
            log.warn("清理过期曝光日志失败: {}", e.getMessage());
        }
    }

    /**
     * 景点近 N 天曝光去重用户数归一化到 0~1；没有曝光的景点返回 0。
     */
    public double popularity(String poiId) {
        if (poiId == null) {
            return 0.0;
        }
        return popularity.getOrDefault(poiId, 0.0);
    }

    public Map<String, Double> popularitySnapshot() {
        return popularity;
    }

    @Scheduled(fixedDelayString = "${app.tag-vector.popularity-refresh-ms:300000}")
    public void refreshPopularity() {
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, popularityWindowDays));
            List<TagPopularity> rows = impressionMapper.selectPopularitySince(since);
            if (rows == null || rows.isEmpty()) {
                popularity = Map.of();
                return;
            }
            int max = rows.stream()
                    .map(TagPopularity::getUserCount)
                    .filter(value -> value != null && value > 0)
                    .max(Integer::compareTo)
                    .orElse(0);
            if (max <= 0) {
                popularity = Map.of();
                return;
            }
            double denominator = Math.log1p(max);
            Map<String, Double> normalized = new LinkedHashMap<>();
            for (TagPopularity row : rows) {
                if (row == null || row.getPoiId() == null || row.getUserCount() == null) {
                    continue;
                }
                normalized.put(row.getPoiId(),
                        Math.max(0.0, Math.min(1.0, Math.log1p(row.getUserCount()) / denominator)));
            }
            popularity = Map.copyOf(normalized);
        } catch (Exception e) {
            log.warn("刷新曝光热度失败，保留上一次快照: {}", e.getMessage());
        }
    }
}
