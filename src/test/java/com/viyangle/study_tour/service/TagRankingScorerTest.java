package com.viyangle.study_tour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagRankingScorerTest {

    @Mock private TagVectorService tagVectorService;
    @Mock private TagImpressionService tagImpressionService;
    @Mock private AccountTagAffinityService accountTagAffinityService;
    @Mock private TagRankingModelService rankingModelService;

    @InjectMocks
    private TagRankingScorer scorer;

    @Test
    @DisplayName("按 prior/learned/热度/会话/用户偏好融合成可解释分数")
    void combinesFeaturesWithDefaultWeights() {
        when(tagVectorService.isReady()).thenReturn(true);
        when(tagVectorService.serveMode()).thenReturn("PERSONALIZED");
        when(tagVectorService.priorVector("p1")).thenReturn(new double[]{0.8, 0.0});
        when(tagVectorService.learnedVector("p1")).thenReturn(new double[]{0.4, 0.0});
        when(tagImpressionService.popularity("p1")).thenReturn(0.5);
        when(accountTagAffinityService.affinity(1L)).thenReturn(new double[]{0.6, 0.0});
        when(rankingModelService.coefficients()).thenReturn(Map.of());
        when(rankingModelService.intercept()).thenReturn(0.0);

        TagRankingScorer.RankingScore score = scorer.score(
                "p1", new double[]{1.0, 0.0}, new double[]{1.0, 0.0}, 1L, 0.0);

        assertEquals(0.8, score.priorMatch(), 1e-9);
        assertEquals(0.4, score.learnedMatch(), 1e-9);
        assertEquals(0.5, score.popularity(), 1e-9);
        assertEquals(1.0, score.sessionMatch(), 1e-9);
        assertEquals(1.0, score.userMatch(), 1e-9);
        // 0.45*0.8 + 0.20*0.4 + 0.15*0.5 + 0.15*1 + 0.10*1 = 0.765
        assertEquals(0.765, score.total(), 1e-9);
    }

    @Test
    @DisplayName("空意图时只保留热度等非意图特征")
    void emptyIntentKeepsNonIntentFeatures() {
        when(tagVectorService.isReady()).thenReturn(true);
        when(tagVectorService.priorVector("p1")).thenReturn(new double[]{0.8, 0.2});
        when(tagImpressionService.popularity("p1")).thenReturn(0.5);
        when(rankingModelService.coefficients()).thenReturn(Map.of());
        when(rankingModelService.intercept()).thenReturn(0.0);

        TagRankingScorer.RankingScore score = scorer.score("p1", new double[0]);

        assertEquals(0.0, score.priorMatch(), 1e-9);
        assertEquals(0.0, score.learnedMatch(), 1e-9);
        assertEquals(0.5, score.popularity(), 1e-9);
        assertEquals(0.075, score.total(), 1e-9);
    }
}
