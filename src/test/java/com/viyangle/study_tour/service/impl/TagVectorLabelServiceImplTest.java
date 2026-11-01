package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.aiservice.AttractionTagScorerAi;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.mapper.AttractionTagMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.AttractionTag;
import com.viyangle.study_tour.pojo.TagDimension;
import com.viyangle.study_tour.rag.StudyTourTagClassifier;
import com.viyangle.study_tour.service.TagVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TagVectorLabelServiceImplTest {

    private static final String POI = "B00190BBCZ";

    @Mock
    private AttractionTagMapper attractionTagMapper;

    @Mock
    private AttractionMapper attractionMapper;

    @Mock
    private TagVectorService tagVectorService;

    @Mock
    private StudyTourTagClassifier classifier;

    @Mock
    private AttractionTagScorerAi scorer;

    @InjectMocks
    private TagVectorLabelServiceImpl service;

    @BeforeEach
    void setUp() {
        when(tagVectorService.dimensions()).thenReturn(dimensions());
    }

    // ==================== 测试数据 ====================

    private static List<TagDimension> dimensions() {
        String[][] rows = {
                {"HISTORY_HUMANITIES", "历史人文"},
                {"MUSEUM_STUDY", "博物馆研学"},
                {"INTANGIBLE_HERITAGE", "非遗体验"},
                {"TECH_EXPLORATION", "科技探索"},
                {"NATURE_ECOLOGY", "自然生态"},
                {"GEO_GEOLOGY", "地理地质"},
                {"AEROSPACE", "航天航空"},
                {"FARMING_LABOR", "农耕劳动"},
                {"ART_EDUCATION", "艺术美育"},
                {"RED_EDUCATION", "红色教育"},
                {"UNIVERSITY_VISIT", "高校参访"},
                {"CAREER_ENLIGHTENMENT", "职业启蒙"},
                {"ENGLISH_PRACTICE", "英语实践"},
                {"PHOTOGRAPHY", "摄影记录"},
                {"PARENT_CHILD", "亲子互动"}
        };
        List<TagDimension> dimensions = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            TagDimension dimension = new TagDimension();
            dimension.setDimVersion(1);
            dimension.setOrdinal(i + 1);
            dimension.setTagId((long) (i + 1));
            dimension.setCode(rows[i][0]);
            dimension.setName(rows[i][1]);
            dimension.setEnabled(true);
            dimensions.add(dimension);
        }
        return dimensions;
    }

    private static Attraction attraction() {
        Attraction attraction = new Attraction();
        attraction.setPoiId(POI);
        attraction.setName("南京博物院");
        attraction.setType("科教文化服务;博物馆;博物馆");
        attraction.setAddress("中山东路321号");
        attraction.setPname("江苏省");
        attraction.setCityname("南京市");
        attraction.setAdname("玄武区");
        return attraction;
    }

    private static AttractionTag manualTag(long tagId) {
        AttractionTag tag = new AttractionTag();
        tag.setPoiId(POI);
        tag.setTagId(tagId);
        tag.setSource("MANUAL");
        return tag;
    }

    private void stubRuleScores(Map<String, Double> scores) {
        when(classifier.score(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(scores);
    }

    private void enableLlm() {
        ReflectionTestUtils.setField(service, "llmApiKey", "test-key");
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("真实规则分类器的中文标签名能映射到维度 code")
    void realClassifierNamesMapToDimensionCodes() {
        ReflectionTestUtils.setField(service, "classifier", new StudyTourTagClassifier());
        when(attractionTagMapper.selectByPoiId(POI)).thenReturn(List.of());
        when(attractionTagMapper.insertWithWeight(anyString(), anyLong(), anyString(), anyDouble(), anyDouble()))
                .thenReturn(1);

        int written = service.autoLabel(attraction());

        assertTrue(written > 0);
    }

    @Test
    @DisplayName("没有 API Key 时走规则打分，并按 RULE 写入权重")
    void fallsBackToRuleScoresWithoutApiKey() {
        when(attractionTagMapper.selectByPoiId(POI)).thenReturn(List.of());
        stubRuleScores(Map.of("历史人文", 0.8, "博物馆研学", 0.6));

        int written = service.autoLabel(attraction());

        assertEquals(2, written);
        verify(attractionTagMapper).deleteAutoWeightsByPoiIdAndTagIds(eq(POI), anyList());
        verify(attractionTagMapper).insertWithWeight(POI, 1L, "RULE", 0.8, 1.5);
        verify(attractionTagMapper).insertWithWeight(POI, 2L, "RULE", 0.6, 1.5);
        verify(tagVectorService).reload();
    }

    @Test
    @DisplayName("LLM 返回合法 JSON 时优先使用 LLM_V2 权重")
    void usesLlmScoresWhenAvailable() {
        enableLlm();
        when(attractionTagMapper.selectByPoiId(POI)).thenReturn(List.of());
        when(scorer.score(anyString())).thenReturn("""
                ```json
                {"HISTORY_HUMANITIES": 0.9, "MUSEUM_STUDY": 0.7}
                ```
                """);

        int written = service.autoLabel(attraction());

        assertEquals(2, written);
        verify(attractionTagMapper).insertWithWeight(POI, 1L, "LLM_V2", 0.9, 3.0);
        verify(attractionTagMapper).insertWithWeight(POI, 2L, "LLM_V2", 0.7, 3.0);
        verify(classifier, never()).score(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("LLM 失败时回退规则，不阻断景点同步")
    void fallsBackToRuleWhenLlmFails() {
        enableLlm();
        when(attractionTagMapper.selectByPoiId(POI)).thenReturn(List.of());
        when(scorer.score(anyString())).thenThrow(new RuntimeException("timeout"));
        stubRuleScores(Map.of("历史人文", 0.5));

        int written = service.autoLabel(attraction());

        assertEquals(1, written);
        verify(attractionTagMapper).insertWithWeight(POI, 1L, "RULE", 0.5, 1.5);
    }

    @Test
    @DisplayName("MANUAL 权重不被自动任务覆盖")
    void preservesManualWeights() {
        enableLlm();
        when(attractionTagMapper.selectByPoiId(POI)).thenReturn(List.of(manualTag(1L)));
        when(scorer.score(anyString())).thenReturn("{\"HISTORY_HUMANITIES\": 0.9, \"MUSEUM_STUDY\": 0.7}");

        int written = service.autoLabel(attraction());

        assertEquals(1, written);
        verify(attractionTagMapper, never())
                .insertWithWeight(eq(POI), eq(1L), anyString(), anyDouble(), anyDouble());
        verify(attractionTagMapper).insertWithWeight(POI, 2L, "LLM_V2", 0.7, 3.0);
    }

    @Test
    @DisplayName("LLM 返回超过 5 个高权重维度时只保留前 5 个")
    void trimsHighDimensionsToFive() {
        enableLlm();
        when(attractionTagMapper.selectByPoiId(POI)).thenReturn(List.of());
        when(scorer.score(anyString())).thenReturn("""
                {"HISTORY_HUMANITIES":0.9,"MUSEUM_STUDY":0.8,"RED_EDUCATION":0.7,
                 "NATURE_ECOLOGY":0.6,"ART_EDUCATION":0.5,"TECH_EXPLORATION":0.5}
                """);

        int written = service.autoLabel(attraction());

        assertEquals(5, written);
    }

    @Test
    @DisplayName("补标只处理没有自动权重的景点，并在结束后刷新向量")
    void backfillMissingLabelsOnlyUnlabeledAttractions() {
        when(attractionMapper.selectPoiIdsWithoutTagVector(10)).thenReturn(List.of(POI));
        when(attractionMapper.selectByPoiId(POI)).thenReturn(attraction());
        when(attractionTagMapper.selectByPoiId(POI)).thenReturn(List.of());
        stubRuleScores(Map.of("历史人文", 0.8));

        int labeled = service.backfillMissing(10);

        assertEquals(1, labeled);
        verify(attractionTagMapper).insertWithWeight(POI, 1L, "RULE", 0.8, 1.5);
        verify(tagVectorService).reload();
    }

    @Test
    @DisplayName("补标是强制重算：存量 MANUAL 种子也会被重新打成 RULE 权重")
    void backfillForceRegradesManualRows() {
        when(attractionMapper.selectPoiIdsWithoutTagVector(10)).thenReturn(List.of(POI));
        when(attractionMapper.selectByPoiId(POI)).thenReturn(attraction());
        when(attractionTagMapper.selectByPoiId(POI)).thenReturn(List.of(manualTag(1L)));
        stubRuleScores(Map.of("历史人文", 0.8));

        int labeled = service.backfillMissing(10);

        assertEquals(1, labeled);
        verify(attractionTagMapper).deleteWeightsByPoiIdAndTagIds(eq(POI), anyList());
        verify(attractionTagMapper).insertWithWeight(POI, 1L, "RULE", 0.8, 1.5);
    }

    @Test
    @DisplayName("关闭自动打分后不触发任何写库")
    void disabledServiceDoesNothing() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertEquals(0, service.autoLabel(attraction()));
        verifyNoInteractions(attractionTagMapper);
    }

    @Test
    @DisplayName("重算不存在的景点返回 0")
    void relabelUnknownPoiReturnsZero() {
        when(attractionMapper.selectByPoiId("unknown")).thenReturn(null);

        assertEquals(0, service.relabel("unknown"));
    }
}
