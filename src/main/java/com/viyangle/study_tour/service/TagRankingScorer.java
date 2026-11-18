package com.viyangle.study_tour.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 把「先验匹配、学习层匹配、热度、会话匹配、用户偏好、距离」融成一个可解释分数。
 *
 * <p>系数优先来自 {@link TagRankingModelService} 的 active pairwise 模型；没有模型时
 * 使用配置中的默认权重，保证在训练数据不足时仍可工作。</p>
 */
@Slf4j
@Service
public class TagRankingScorer {

    public static final String F_PRIOR_MATCH = "prior_match";
    public static final String F_LEARNED_MATCH = "learned_match";
    public static final String F_POPULARITY = "popularity";
    public static final String F_SESSION_MATCH = "session_match";
    public static final String F_USER_MATCH = "user_match";
    public static final String F_QUALITY = "quality";
    public static final String F_DISTANCE = "distance";

    @Autowired(required = false)
    private TagVectorService tagVectorService;

    @Autowired(required = false)
    private TagImpressionService tagImpressionService;

    @Autowired(required = false)
    private AccountTagAffinityService accountTagAffinityService;

    @Autowired(required = false)
    private TagRankingModelService rankingModelService;

    @Value("${app.tag-vector.ranking.prior-match-weight:0.45}")
    private double priorMatchWeight = 0.45;

    @Value("${app.tag-vector.ranking.learned-match-weight:0.20}")
    private double learnedMatchWeight = 0.20;

    @Value("${app.tag-vector.ranking.popularity-weight:0.15}")
    private double popularityWeight = 0.15;

    @Value("${app.tag-vector.ranking.session-match-weight:0.15}")
    private double sessionMatchWeight = 0.15;

    @Value("${app.tag-vector.ranking.user-match-weight:0.10}")
    private double userMatchWeight = 0.10;

    @Value("${app.tag-vector.ranking.quality-weight:0.05}")
    private double qualityWeight = 0.05;

    @Value("${app.tag-vector.ranking.distance-weight:-0.10}")
    private double distanceWeight = -0.10;

    public RankingScore score(String poiId,
                              double[] intent,
                              double[] sessionIntent,
                              Long accountId,
                              double distanceMinutes) {
        return compute(poiId, intent, sessionIntent, accountId, distanceMinutes,
                includeLearnedOnline(), includeUserOnline());
    }

    public RankingScore score(String poiId, double[] intent) {
        return score(poiId, intent, intent, null, 0.0);
    }

    /**
     * SHADOW 模式使用：线上仍按当前模式服务，但记录带学习层/用户偏好的反事实分数。
     */
    public RankingScore shadowScore(String poiId,
                                    double[] intent,
                                    double[] sessionIntent,
                                    Long accountId,
                                    double distanceMinutes) {
        return compute(poiId, intent, sessionIntent, accountId, distanceMinutes, true, true);
    }

    private RankingScore compute(String poiId,
                                 double[] intent,
                                 double[] sessionIntent,
                                 Long accountId,
                                 double distanceMinutes,
                                 boolean includeLearned,
                                 boolean includeUser) {
        if (poiId == null || tagVectorService == null || !tagVectorService.isReady()) {
            return RankingScore.zero();
        }
        double[] prior = tagVectorService.priorVector(poiId);
        double[] learned = includeLearned ? tagVectorService.learnedVector(poiId) : null;
        double priorMatch = weightedMatch(prior, intent);
        double learnedMatch = weightedMatch(learned, intent);
        double sessionMatch = cosine(sessionIntent, prior);
        double userMatch = includeUser ? userMatch(accountId, prior) : 0.0;
        double popularity = tagImpressionService == null ? 0.0 : tagImpressionService.popularity(poiId);
        double quality = 0.0;
        double distance = Math.max(0.0, Math.min(1.0, distanceMinutes / 60.0));

        double intercept = rankingModelService == null ? 0.0 : rankingModelService.intercept();
        double total = intercept
                + coefficient(F_PRIOR_MATCH, priorMatchWeight) * priorMatch
                + coefficient(F_LEARNED_MATCH, learnedMatchWeight) * learnedMatch
                + coefficient(F_POPULARITY, popularityWeight) * popularity
                + coefficient(F_SESSION_MATCH, sessionMatchWeight) * sessionMatch
                + coefficient(F_USER_MATCH, userMatchWeight) * userMatch
                + coefficient(F_QUALITY, qualityWeight) * quality
                + coefficient(F_DISTANCE, distanceWeight) * distance;
        return new RankingScore(total, priorMatch, learnedMatch, popularity,
                sessionMatch, userMatch, quality, distance);
    }

    private boolean includeLearnedOnline() {
        if (tagVectorService == null) {
            return false;
        }
        String mode = tagVectorService.serveMode();
        return "BLEND".equalsIgnoreCase(mode) || "PERSONALIZED".equalsIgnoreCase(mode);
    }

    private boolean includeUserOnline() {
        if (tagVectorService == null) {
            return false;
        }
        return "PERSONALIZED".equalsIgnoreCase(tagVectorService.serveMode());
    }

    private double coefficient(String feature, double fallback) {
        if (rankingModelService == null) {
            return fallback;
        }
        Map<String, Double> coefficients = rankingModelService.coefficients();
        return coefficients.getOrDefault(feature, fallback);
    }

    private double userMatch(Long accountId, double[] prior) {
        if (accountId == null || accountTagAffinityService == null) {
            return 0.0;
        }
        double[] affinity = accountTagAffinityService.affinity(accountId);
        return cosine(affinity, prior);
    }

    private static double weightedMatch(double[] vector, double[] intent) {
        if (vector == null || intent == null || vector.length == 0 || intent.length == 0) {
            return 0.0;
        }
        int size = Math.min(vector.length, intent.length);
        double sumIntent = 0.0;
        double dot = 0.0;
        for (int i = 0; i < size; i++) {
            sumIntent += intent[i];
            dot += intent[i] * vector[i];
        }
        if (sumIntent <= 0.0) {
            return 0.0;
        }
        return clamp(dot / sumIntent);
    }

    private static double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0) {
            return 0.0;
        }
        int size = Math.min(left.length, right.length);
        double dot = 0.0;
        double normLeft = 0.0;
        double normRight = 0.0;
        for (int i = 0; i < size; i++) {
            dot += left[i] * right[i];
            normLeft += left[i] * left[i];
            normRight += right[i] * right[i];
        }
        if (normLeft <= 0.0 || normRight <= 0.0) {
            return 0.0;
        }
        return clamp(dot / (Math.sqrt(normLeft) * Math.sqrt(normRight)));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record RankingScore(
            double total,
            double priorMatch,
            double learnedMatch,
            double popularity,
            double sessionMatch,
            double userMatch,
            double quality,
            double distance) {

        private static RankingScore zero() {
            return new RankingScore(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
