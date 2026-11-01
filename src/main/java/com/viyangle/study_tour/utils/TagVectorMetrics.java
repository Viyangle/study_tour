package com.viyangle.study_tour.utils;

import java.util.List;
import java.util.Set;

/**
 * 离线评估指标：Recall@K 与 NDCG@K。
 *
 * <p>只看排序列表里的命中位置，不依赖 Spring 或数据库，便于在工具类和单测里复用。</p>
 */
public final class TagVectorMetrics {

    private TagVectorMetrics() {
    }

    public static double recallAtK(List<String> ranked, Set<String> relevant, int k) {
        if (ranked == null || relevant == null || relevant.isEmpty() || k <= 0) {
            return 0.0;
        }
        int limit = Math.min(k, ranked.size());
        int hits = 0;
        for (int i = 0; i < limit; i++) {
            if (relevant.contains(ranked.get(i))) {
                hits++;
            }
        }
        return (double) hits / relevant.size();
    }

    public static double ndcgAtK(List<String> ranked, Set<String> relevant, int k) {
        if (ranked == null || relevant == null || relevant.isEmpty() || k <= 0) {
            return 0.0;
        }
        double dcg = 0.0;
        int limit = Math.min(k, ranked.size());
        for (int i = 0; i < limit; i++) {
            if (relevant.contains(ranked.get(i))) {
                dcg += 1.0 / Math.log(i + 2);
            }
        }
        int ideal = Math.min(k, relevant.size());
        double idcg = 0.0;
        for (int i = 0; i < ideal; i++) {
            idcg += 1.0 / Math.log(i + 2);
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }
}
