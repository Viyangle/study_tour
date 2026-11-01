package com.viyangle.study_tour.utils;

import com.viyangle.study_tour.pojo.AIRoutePlan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 校验并归一化模型返回的路线 tag 向量。
 *
 * <p>兼容旧模型：没有 {@code tagVector} 时只看 {@code tag}；
 * 有向量时丢弃非法维度、把权重钳到 0~1，并把 {@code tag} 重设为权重最高的主题。</p>
 */
public final class AiRoutePlanTags {

    private AiRoutePlanTags() {
    }

    public static void normalize(AIRoutePlan plan, Set<String> allowedTags) {
        if (plan == null) {
            throw new IllegalArgumentException("AI route plan is empty");
        }

        Map<String, Double> cleaned = new LinkedHashMap<>();
        if (plan.getTagVector() != null) {
            for (Map.Entry<String, Double> entry : plan.getTagVector().entrySet()) {
                String name = entry.getKey() == null ? null : entry.getKey().trim();
                Double weight = entry.getValue();
                if (name == null || name.isEmpty() || weight == null || weight.isNaN() || weight <= 0) {
                    continue;
                }
                if (allowedTags != null && !allowedTags.contains(name)) {
                    continue;
                }
                cleaned.put(name, Math.min(1.0, weight));
            }
        }

        if (!cleaned.isEmpty()) {
            plan.setTagVector(cleaned);
            plan.setTag(cleaned.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null));
        }

        if (plan.getTag() == null || plan.getTag().isBlank()) {
            throw new IllegalArgumentException("AI route plan tag is empty");
        }
        if (allowedTags != null && !allowedTags.contains(plan.getTag())) {
            throw new IllegalArgumentException("AI route plan tag is invalid: " + plan.getTag());
        }
    }
}
