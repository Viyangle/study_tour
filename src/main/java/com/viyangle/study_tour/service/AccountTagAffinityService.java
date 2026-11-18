package com.viyangle.study_tour.service;

import com.viyangle.study_tour.mapper.AccountTagAffinityMapper;
import com.viyangle.study_tour.pojo.AccountTagAffinity;
import com.viyangle.study_tour.pojo.TagDimension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户-标签长期偏好：只参与该用户排序，不写回景点先验。
 */
@Slf4j
@Service
public class AccountTagAffinityService {

    @Autowired
    private AccountTagAffinityMapper affinityMapper;

    @Autowired(required = false)
    private TagVectorService tagVectorService;

    @Value("${app.tag-vector.affinity-alpha:0.2}")
    private double alpha = 0.2;

    /**
     * 用一个会话的意图向量更新用户偏好；没有 intent 的维度不动。
     */
    public void update(Long accountId, double[] intent) {
        if (accountId == null || intent == null || intent.length == 0
                || tagVectorService == null || !tagVectorService.isReady()) {
            return;
        }
        List<TagDimension> dimensions = tagVectorService.dimensions();
        if (dimensions.isEmpty()) {
            return;
        }
        Map<Long, Double> existing = current(accountId);
        double step = Math.max(0.0, Math.min(1.0, alpha));
        try {
            for (int i = 0; i < dimensions.size() && i < intent.length; i++) {
                if (intent[i] <= 0) {
                    continue;
                }
                TagDimension dimension = dimensions.get(i);
                if (dimension.getTagId() == null) {
                    continue;
                }
                double old = existing.getOrDefault(dimension.getTagId(), 0.0);
                double updated = Math.max(0.0, Math.min(1.0, old * (1.0 - step) + intent[i] * step));
                affinityMapper.upsert(accountId, dimension.getTagId(), updated);
            }
        } catch (Exception e) {
            log.warn("更新用户标签偏好失败: accountId={}, {}", accountId, e.getMessage());
        }
    }

    /**
     * 返回当前维度顺序下的用户偏好向量；没有记录的维度为 0。
     */
    public double[] affinity(Long accountId) {
        if (accountId == null || tagVectorService == null || !tagVectorService.isReady()) {
            return new double[0];
        }
        List<TagDimension> dimensions = tagVectorService.dimensions();
        double[] result = new double[dimensions.size()];
        Map<Long, Double> values = current(accountId);
        for (int i = 0; i < dimensions.size(); i++) {
            TagDimension dimension = dimensions.get(i);
            if (dimension.getTagId() != null) {
                result[i] = clamp(values.getOrDefault(dimension.getTagId(), 0.0));
            }
        }
        return result;
    }

    private Map<Long, Double> current(Long accountId) {
        Map<Long, Double> result = new HashMap<>();
        try {
            List<AccountTagAffinity> rows = affinityMapper.selectByAccountId(accountId);
            if (rows != null) {
                for (AccountTagAffinity row : rows) {
                    if (row != null && row.getTagId() != null && row.getAffinity() != null) {
                        result.put(row.getTagId(), clamp(row.getAffinity()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取用户标签偏好失败: accountId={}, {}", accountId, e.getMessage());
        }
        return result;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
