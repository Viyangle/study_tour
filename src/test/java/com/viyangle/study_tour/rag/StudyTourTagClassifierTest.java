package com.viyangle.study_tour.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudyTourTagClassifierTest {

    private final StudyTourTagClassifier classifier = new StudyTourTagClassifier();

    @Test
    @DisplayName("classify 保持原来的标签列表行为")
    void classifyReturnsMatchedTagNames() {
        List<String> tags = classifier.classify("南京博物院", "博物馆");

        assertTrue(tags.contains("博物馆研学"));
        assertEquals("博物馆研学", classifier.primaryTag(tags));
    }

    @Test
    @DisplayName("score 按命中关键词数量给出 0~1 的强度")
    void scoreGradesByKeywordHits() {
        Map<String, Double> scores = classifier.score("南京博物院", "博物馆");

        assertTrue(scores.containsKey("博物馆研学"));
        assertTrue(scores.get("博物馆研学") >= 0.5 && scores.get("博物馆研学") <= 1.0);
        assertFalse(scores.containsKey("红色教育"));
    }

    @Test
    @DisplayName("多个关键词命中时强度高于单个命中")
    void moreHitsScoreHigher() {
        Map<String, Double> twoHits = classifier.score("抗日烈士纪念馆");
        Map<String, Double> oneHit = classifier.score("纪念馆");

        assertTrue(twoHits.get("红色教育") > oneHit.getOrDefault("红色教育", 0.0));
        assertTrue(oneHit.containsKey("历史人文"));
    }

    @Test
    @DisplayName("无命中时回退到默认标签，避免全零向量")
    void emptyInputFallsBackToDefaultTag() {
        Map<String, Double> scores = classifier.score("");

        assertEquals(1, scores.size());
        assertEquals(0.3, scores.get(StudyTourTagClassifier.DEFAULT_TAG), 1e-9);
    }
}
