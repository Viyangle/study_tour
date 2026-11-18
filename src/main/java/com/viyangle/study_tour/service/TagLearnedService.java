package com.viyangle.study_tour.service;

import com.viyangle.study_tour.mapper.AttractionTagLearnedAccountMapper;
import com.viyangle.study_tour.mapper.AttractionTagLearnedMapper;
import com.viyangle.study_tour.pojo.AttractionTagLearned;
import com.viyangle.study_tour.pojo.TagSupportCount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局学习层的读写：只写 {@code attraction_tag_learned}，不碰语义先验。
 *
 * <p>学习层用 distinct users 做支持度收缩，避免 1 个用户刷 10 次就被当成 10 个人。
 * 这里不直接服务排序，排序由 {@link TagVectorService} 按 serve-mode 合成。</p>
 */
@Slf4j
@Service
public class TagLearnedService {

    @Autowired
    private AttractionTagLearnedMapper learnedMapper;

    @Autowired
    private AttractionTagLearnedAccountMapper learnedAccountMapper;

    @Value("${app.tag-vector.pos-half-life-days:90}")
    private double posHalfLifeDays = 90.0;

    @Value("${app.tag-vector.neg-half-life-days:180}")
    private double negHalfLifeDays = 180.0;

    @Value("${app.tag-vector.support-window-days:30}")
    private int supportWindowDays = 30;

    /**
     * 写入一批维度的证据；调用方负责事务。
     */
    public void applyEvidence(String poiId,
                              Map<Long, double[]> evidence,
                              LocalDateTime now) {
        if (poiId == null || evidence == null || evidence.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, double[]> entry : evidence.entrySet()) {
            Long tagId = entry.getKey();
            double[] delta = entry.getValue();
            if (tagId == null || delta == null || delta.length < 2) {
                continue;
            }
            AttractionTagLearned row = learnedMapper.selectByPoiAndTag(poiId, tagId);
            double pos = delta[0];
            double neg = delta[1];
            if (row != null) {
                pos += decay(row.getPosCount(), row.getLastDecayAt(), now, posHalfLifeDays);
                neg += decay(row.getNegCount(), row.getLastDecayAt(), now, negHalfLifeDays);
            }
            learnedMapper.upsertEvidence(poiId, tagId, pos, neg);
        }
    }

    /**
     * 记录用户贡献，然后刷新 distinct_users。调用方保证 accountId 非空。
     */
    public void touchUsers(String poiId,
                           List<Long> tagIds,
                           Long accountId,
                           LocalDateTime now) {
        if (poiId == null || tagIds == null || tagIds.isEmpty() || accountId == null) {
            return;
        }
        try {
            learnedAccountMapper.batchTouch(poiId, tagIds, accountId, now);
            refreshDistinctUsers(poiId, tagIds, now);
        } catch (Exception e) {
            // 支持度只是收缩权重，失败不能回滚主证据。
            log.warn("刷新学习层 distinct_users 失败: poiId={}, accountId={}, {}",
                    poiId, accountId, e.getMessage());
        }
    }

    public void refreshDistinctUsers(String poiId, List<Long> tagIds, LocalDateTime now) {
        if (poiId == null || tagIds == null || tagIds.isEmpty()) {
            return;
        }
        LocalDateTime since = now.minusDays(Math.max(1, supportWindowDays));
        List<TagSupportCount> counts = learnedAccountMapper.countDistinctUsers(poiId, tagIds, since);
        Map<Long, Integer> byTag = new HashMap<>();
        if (counts != null) {
            for (TagSupportCount count : counts) {
                if (count != null && count.getTagId() != null) {
                    byTag.put(count.getTagId(), count.getUserCount() == null ? 0 : count.getUserCount());
                }
            }
        }
        for (Long tagId : tagIds) {
            if (tagId != null) {
                learnedMapper.updateDistinctUsers(poiId, tagId, byTag.getOrDefault(tagId, 0));
            }
        }
    }

    private double decay(Double value, LocalDateTime last, LocalDateTime now, double halfLifeDays) {
        if (value == null || value <= 0) {
            return 0.0;
        }
        if (last == null || halfLifeDays <= 0) {
            return value;
        }
        double days = Duration.between(last, now).toMillis() / 86_400_000.0;
        if (days <= 0) {
            return value;
        }
        return value * Math.pow(0.5, days / halfLifeDays);
    }
}
