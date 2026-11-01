package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.RouteAttractionMapper;
import com.viyangle.study_tour.mapper.RouteMapper;
import com.viyangle.study_tour.mapper.RouteTagWeightMapper;
import com.viyangle.study_tour.pojo.AttractionTagVector;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.pojo.RouteTagWeight;
import com.viyangle.study_tour.pojo.TagDimension;
import com.viyangle.study_tour.service.TagVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouteTagVectorServiceImplTest {

    private static final Long ROUTE_ID = 100L;

    @Mock private RouteTagWeightMapper routeTagWeightMapper;
    @Mock private RouteAttractionMapper routeAttractionMapper;
    @Mock private RouteMapper routeMapper;
    @Mock private TagVectorService tagVectorService;

    @InjectMocks
    private RouteTagVectorServiceImpl service;

    @BeforeEach
    void setUp() {
        when(tagVectorService.isReady()).thenReturn(true);
        when(tagVectorService.dimensions()).thenReturn(dimensions());
    }

    private static List<TagDimension> dimensions() {
        List<TagDimension> dimensions = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            TagDimension dimension = new TagDimension();
            dimension.setDimVersion(1);
            dimension.setOrdinal(i);
            dimension.setTagId((long) i);
            dimension.setCode("DIM_" + i);
            dimension.setName("维度" + i);
            dimension.setEnabled(true);
            dimensions.add(dimension);
        }
        return dimensions;
    }

    private static RouteAttraction stop(String poiId, Integer duration) {
        RouteAttraction stop = new RouteAttraction();
        stop.setRouteId(ROUTE_ID);
        stop.setPoiId(poiId);
        stop.setRecommendedDuration(duration);
        return stop;
    }

    private static double[] vector(int index) {
        double[] vector = new double[15];
        vector[index] = 1.0;
        return vector;
    }

    @Test
    @DisplayName("路线向量按时长加权聚合，argmax 回写 routes.tag")
    void rebuildAggregatesByDurationAndUpdatesPrimaryTag() {
        RouteAttraction first = stop("p1", 60);
        RouteAttraction second = stop("p2", 120);
        when(tagVectorService.vector("p1")).thenReturn(vector(0));
        when(tagVectorService.vector("p2")).thenReturn(vector(1));

        String primary = service.rebuild(ROUTE_ID, List.of(first, second));

        assertEquals("维度2", primary);
        verify(routeTagWeightMapper).deleteByRouteId(ROUTE_ID);
        ArgumentCaptor<List<RouteTagWeight>> captor = ArgumentCaptor.forClass(List.class);
        verify(routeTagWeightMapper).batchInsert(captor.capture());
        assertEquals(2, captor.getValue().size());
        assertEquals(0.333, captor.getValue().get(0).getWeight(), 1e-9);
        assertEquals(0.667, captor.getValue().get(1).getWeight(), 1e-9);
        verify(routeMapper).updateTag(ROUTE_ID, "维度2");
    }

    @Test
    @DisplayName("模型 tagVector 按配置权重与站点聚合向量融合")
    void modelVectorIsBlendedWithDerivedVector() {
        ReflectionTestUtils.setField(service, "routeModelWeight", 0.3);
        when(tagVectorService.vector("p1")).thenReturn(vector(0));

        String primary = service.rebuild(ROUTE_ID, List.of(stop("p1", 60)),
                Map.of("维度2", 0.8));

        assertEquals("维度1", primary);
        ArgumentCaptor<List<RouteTagWeight>> captor = ArgumentCaptor.forClass(List.class);
        verify(routeTagWeightMapper).batchInsert(captor.capture());
        assertEquals(0.7, captor.getValue().get(0).getWeight(), 1e-9);
        assertEquals(0.24, captor.getValue().get(1).getWeight(), 1e-9);
        assertEquals("LLM_BLEND", captor.getValue().get(0).getSource());
    }

    @Test
    @DisplayName("只有模型向量、没有站点向量时仍能写库")
    void modelVectorWorksWithoutStops() {
        when(tagVectorService.vector("p1")).thenReturn(new double[15]);

        String primary = service.rebuild(ROUTE_ID, List.of(stop("p1", 60)),
                Map.of("DIM_2", 0.9));

        assertEquals("维度2", primary);
        ArgumentCaptor<List<RouteTagWeight>> captor = ArgumentCaptor.forClass(List.class);
        verify(routeTagWeightMapper).batchInsert(captor.capture());
        assertEquals(0.9, captor.getValue().get(0).getWeight(), 1e-9);
    }

    @Test
    @DisplayName("已有 route_tag_weight 时优先读取持久化向量")
    void viewUsesPersistedRows() {
        when(routeTagWeightMapper.selectByRouteId(ROUTE_ID)).thenReturn(List.of(
                new RouteTagWeight(ROUTE_ID, 1L, 0.8, "DERIVED", 2, null),
                new RouteTagWeight(ROUTE_ID, 2L, 0.2, "DERIVED", 2, null)
        ));

        AttractionTagVector view = service.view(ROUTE_ID);

        assertNotNull(view);
        assertEquals("DIM_1", view.getPrimary());
        assertEquals(0.8, view.getWeights().get(0), 1e-9);
        assertEquals(0.2, view.getWeights().get(1), 1e-9);
        assertEquals(2, view.getVectorVersion());
    }

    @Test
    @DisplayName("没有持久化行时按站点实时聚合")
    void viewFallsBackToStops() {
        when(routeTagWeightMapper.selectByRouteId(ROUTE_ID)).thenReturn(List.of());
        when(routeAttractionMapper.selectByRouteId(ROUTE_ID)).thenReturn(List.of(stop("p1", 60)));
        when(tagVectorService.vector("p1")).thenReturn(vector(0));

        AttractionTagVector view = service.view(ROUTE_ID);

        assertNotNull(view);
        assertEquals("DIM_1", view.getPrimary());
        assertEquals(1.0, view.getWeights().get(0), 1e-9);
    }

    @Test
    @DisplayName("向量未就绪时 rebuild 不写库")
    void rebuildSkipsWhenVectorServiceNotReady() {
        when(tagVectorService.isReady()).thenReturn(false);

        assertNull(service.rebuild(ROUTE_ID, List.of(stop("p1", 60))));
        verify(routeTagWeightMapper, never()).deleteByRouteId(ROUTE_ID);
    }

    @Test
    @DisplayName("没有站点或向量时返回空向量")
    void emptyRouteReturnsEmptyVector() {
        when(routeTagWeightMapper.selectByRouteId(ROUTE_ID)).thenReturn(List.of());
        when(routeAttractionMapper.selectByRouteId(ROUTE_ID)).thenReturn(List.of());

        double[] vector = service.vector(ROUTE_ID);

        assertEquals(15, vector.length);
        for (double value : vector) {
            assertEquals(0.0, value, 1e-9);
        }
    }

    @Test
    @DisplayName("rebuild 没有可用站点向量时返回 null")
    void rebuildWithoutStopsReturnsNull() {
        assertNull(service.rebuild(ROUTE_ID, List.of()));
        verify(routeTagWeightMapper, never()).batchInsert(anyList());
    }

    @Test
    @DisplayName("view 对空向量返回空 dims，不抛异常")
    void emptyViewHasNoDims() {
        when(routeTagWeightMapper.selectByRouteId(ROUTE_ID)).thenReturn(List.of());
        when(routeAttractionMapper.selectByRouteId(ROUTE_ID)).thenReturn(List.of());

        AttractionTagVector view = service.view(ROUTE_ID);

        assertNull(view);
    }
}
