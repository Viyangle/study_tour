package com.viyangle.study_tour.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TagVectorMetricsTest {

    @Test
    @DisplayName("Recall@K 只统计前 K 个候选")
    void recallCountsHitsInTopK() {
        List<String> ranked = List.of("a", "b", "c");
        Set<String> relevant = Set.of("a", "c");

        assertEquals(0.5, TagVectorMetrics.recallAtK(ranked, relevant, 2), 1e-9);
        assertEquals(1.0, TagVectorMetrics.recallAtK(ranked, relevant, 3), 1e-9);
        assertEquals(0.0, TagVectorMetrics.recallAtK(ranked, Set.of(), 3), 1e-9);
    }

    @Test
    @DisplayName("NDCG@K 奖励靠前的命中")
    void ndcgRewardsEarlierHits() {
        List<String> good = List.of("a", "b", "c");
        List<String> bad = List.of("c", "b", "a");
        Set<String> relevant = Set.of("a");

        assertEquals(1.0, TagVectorMetrics.ndcgAtK(good, relevant, 3), 1e-9);
        // 命中在第 3 位：DCG = 1/ln(4)，IDCG = 1/ln(2)
        double expected = (1.0 / Math.log(4)) / (1.0 / Math.log(2));
        assertEquals(expected, TagVectorMetrics.ndcgAtK(bad, relevant, 3), 1e-9);
    }

    @Test
    @DisplayName("空相关集或非法 K 返回 0")
    void degenerateInputsReturnZero() {
        List<String> ranked = List.of("a");

        assertEquals(0.0, TagVectorMetrics.recallAtK(ranked, Set.of(), 10), 1e-9);
        assertEquals(0.0, TagVectorMetrics.ndcgAtK(ranked, Set.of("a"), 0), 1e-9);
        assertEquals(0.0, TagVectorMetrics.ndcgAtK(null, Set.of("a"), 10), 1e-9);
    }
}
