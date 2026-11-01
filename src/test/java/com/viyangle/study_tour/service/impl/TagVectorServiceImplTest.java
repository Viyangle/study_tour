package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AttractionTagMapper;
import com.viyangle.study_tour.mapper.TagDimensionMapper;
import com.viyangle.study_tour.pojo.AttractionTag;
import com.viyangle.study_tour.pojo.AttractionTagVector;
import com.viyangle.study_tour.pojo.TagDimension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TagVectorServiceImplTest {

    private static final String POI = "B00190BBCZ";

    @Mock
    private TagDimensionMapper tagDimensionMapper;

    @Mock
    private AttractionTagMapper attractionTagMapper;

    @InjectMocks
    private TagVectorServiceImpl service;

    // ==================== 测试数据 ====================

    private static TagDimension dim(int ordinal, String code, String name, long tagId) {
        TagDimension dimension = new TagDimension();
        dimension.setDimVersion(1);
        dimension.setOrdinal(ordinal);
        dimension.setCode(code);
        dimension.setName(name);
        dimension.setTagId(tagId);
        dimension.setEnabled(true);
        return dimension;
    }

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
            dimensions.add(dim(i + 1, rows[i][0], rows[i][1], i + 1L));
        }
        return dimensions;
    }

    private static AttractionTag edge(String poiId, long tagId, double prior, double strength,
                                      double pos, double neg, Double effective) {
        AttractionTag edge = new AttractionTag();
        edge.setPoiId(poiId);
        edge.setTagId(tagId);
        edge.setSource("LLM");
        edge.setPriorWeight(prior);
        edge.setPriorStrength(strength);
        edge.setPosCount(pos);
        edge.setNegCount(neg);
        edge.setEffectiveWeight(effective);
        edge.setVectorVersion(7);
        edge.setUpdatedAt(LocalDateTime.of(2026, 10, 28, 12, 0));
        return edge;
    }

    private void load(List<AttractionTag> edges) {
        when(tagDimensionMapper.selectAll()).thenReturn(dimensions());
        when(attractionTagMapper.selectAll()).thenReturn(edges);
        service.reload();
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("加载带权边：生成列优先，缺失时用热度公式兜底")
    void loadsWeightedVectorWithFallbackFormula() {
        load(List.of(
                edge(POI, 1L, 1.0, 3.0, 0.0, 0.0, 1.0),
                edge(POI, 2L, 0.2, 3.0, 3.0, 0.0, null)
        ));

        assertTrue(service.isReady());
        assertEquals(1, service.dimVersion());
        assertEquals(15, service.dimensions().size());

        double[] vector = service.vector(POI);
        assertEquals(15, vector.length);
        assertEquals(1.0, vector[0], 1e-9);
        // (3 * 0.2 + 3) / (3 + 3) = 0.6
        assertEquals(0.6, vector[1], 1e-9);
        assertEquals(0.0, vector[2], 1e-9);
    }

    @Test
    @DisplayName("生成列越界时仍钳在 0~1")
    void clampsEffectiveWeightToUnitInterval() {
        load(List.of(
                edge(POI, 1L, 1.0, 3.0, 0.0, 0.0, 1.6),
                edge(POI, 2L, 0.5, 3.0, 0.0, 0.0, -0.3)
        ));

        double[] vector = service.vector(POI);
        assertEquals(1.0, vector[0], 1e-9);
        assertEquals(0.0, vector[1], 1e-9);
    }

    @Test
    @DisplayName("意图向量：只命中的 15 个维度置 1，未知标签忽略")
    void buildsIntentVectorFromTagNames() {
        load(List.of());

        double[] intent = service.intentVector(List.of("历史人文", "博物馆研学", "不存在的标签"));
        assertEquals(1.0, intent[0], 1e-9);
        assertEquals(1.0, intent[1], 1e-9);
        assertEquals(0.0, intent[2], 1e-9);

        assertArrayEquals(new double[15], service.intentVector(List.of()));
    }

    @Test
    @DisplayName("匹配度用意图加权平均，避免多标签景点被余弦压低")
    void matchAveragesAcrossIntentDimensions() {
        load(List.of(
                edge(POI, 1L, 1.0, 3.0, 0.0, 0.0, 1.0),
                edge(POI, 2L, 0.5, 3.0, 0.0, 0.0, 0.5)
        ));

        double[] single = service.intentVector(List.of("历史人文"));
        assertEquals(1.0, service.match(POI, single), 1e-9);
        assertEquals(1.0, service.score(POI, single), 1e-9);

        double[] both = service.intentVector(List.of("历史人文", "博物馆研学"));
        assertEquals(0.75, service.match(POI, both), 1e-9);
        assertEquals(1.5, service.score(POI, both), 1e-9);

        assertEquals(0.0, service.match(POI, new double[15]), 1e-9);
        assertEquals(0.0, service.match("unknown", both), 1e-9);
        assertEquals(0.0, service.score("unknown", both), 1e-9);
    }

    @Test
    @DisplayName("景点间相似度用余弦，未知景点返回 0")
    void computesCosineSimilarityBetweenAttractions() {
        load(List.of(
                edge(POI, 1L, 1.0, 3.0, 0.0, 0.0, 1.0),
                edge("p2", 1L, 1.0, 3.0, 0.0, 0.0, 1.0),
                edge("p2", 2L, 1.0, 3.0, 0.0, 0.0, 1.0)
        ));

        // p1=[1,0,...]，p2=[1,1,0,...] → 1 / sqrt(2)
        assertEquals(1.0 / Math.sqrt(2.0), service.similarity(POI, "p2"), 1e-9);
        assertEquals(1.0, service.similarity(POI, POI), 1e-9);
        assertEquals(0.0, service.similarity(POI, "unknown"), 1e-9);
    }

    @Test
    @DisplayName("对外视图：15 维权重、归一化 share、只返回可见维度")
    void buildsViewWithShareAndVisibleDims() {
        load(List.of(
                edge(POI, 1L, 0.8, 3.0, 0.0, 0.0, 0.8),
                edge(POI, 2L, 0.4, 3.0, 0.0, 0.0, 0.4)
        ));

        AttractionTagVector view = service.view(POI);

        assertEquals(15, view.getWeights().size());
        assertEquals(15, view.getShare().size());
        assertEquals("HISTORY_HUMANITIES", view.getPrimary());
        assertEquals("历史人文", view.getPrimaryName());
        assertEquals(2, view.getDims().size());
        assertEquals("HISTORY_HUMANITIES", view.getDims().get(0).getCode());
        assertEquals(0.8, view.getDims().get(0).getWeight(), 1e-9);
        assertEquals(0.8, view.getDims().get(0).getPrior(), 1e-9);
        assertEquals(0.0, view.getDims().get(0).getSupport(), 1e-9);

        double shareSum = view.getShare().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, shareSum, 1e-6);
        assertEquals(7, view.getVectorVersion());
        assertEquals(LocalDateTime.of(2026, 10, 28, 12, 0), view.getUpdatedAt());
    }

    @Test
    @DisplayName("权重全零的景点不产生 primary，dims 为空")
    void zeroWeightViewHasNoPrimary() {
        load(List.of(edge(POI, 1L, 0.0, 3.0, 0.0, 0.0, 0.0)));

        AttractionTagVector view = service.view(POI);

        assertNull(view.getPrimary());
        assertNull(view.getPrimaryName());
        assertTrue(view.getDims().isEmpty());
        assertTrue(view.getWeights().stream().allMatch(value -> value == 0.0));
    }

    @Test
    @DisplayName("未知景点：视图为 null，向量为全零，匹配度为 0")
    void unknownPoiDegradesGracefully() {
        load(List.of());

        assertNull(service.view("unknown"));
        assertArrayEquals(new double[15], service.vector("unknown"));
        assertEquals(0.0, service.match("unknown", service.intentVector(List.of("历史人文"))), 1e-9);
    }

    @Test
    @DisplayName("维度为空或加载异常时不就绪，调用方可回退旧逻辑")
    void staysNotReadyWhenDimensionsMissingOrLoadFails() {
        when(tagDimensionMapper.selectAll()).thenReturn(List.of());
        service.reload();
        assertFalse(service.isReady());
        assertEquals(0, service.dimVersion());
        assertTrue(service.dimensions().isEmpty());
        assertNull(service.view(POI));

        when(tagDimensionMapper.selectAll()).thenThrow(new IllegalStateException("table missing"));
        service.reload();
        assertFalse(service.isReady());
    }

    @Test
    @DisplayName("只取最新版本且跳过禁用维度")
    void selectsLatestEnabledVersionOnly() {
        List<TagDimension> all = new ArrayList<>(dimensions());
        TagDimension disabled = dim(16, "EXTRA_DIM", "扩展维度", 16L);
        disabled.setDimVersion(2);
        disabled.setEnabled(false);
        TagDimension enabled = dim(1, "HISTORY_HUMANITIES", "历史人文", 1L);
        enabled.setDimVersion(2);
        all.add(disabled);
        all.add(enabled);

        when(tagDimensionMapper.selectAll()).thenReturn(all);
        when(attractionTagMapper.selectAll()).thenReturn(List.of(edge(POI, 1L, 1.0, 3.0, 0.0, 0.0, 1.0)));
        service.reload();

        assertEquals(2, service.dimVersion());
        assertTrue(service.isReady());
        assertEquals(1, service.dimensions().size());
        assertEquals("HISTORY_HUMANITIES", service.dimensions().get(0).getCode());
    }
}
