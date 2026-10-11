package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.aiservice.RouteComposerService;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.pojo.VectorRetrievalResult;
import com.viyangle.study_tour.service.AttractionSyncService;
import com.viyangle.study_tour.service.VectorCandidateRetrieverService;
import com.viyangle.study_tour.utils.AmapTransitClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiRoutePlanningServiceImplTest {

    @Mock
    private RouteComposerService routeComposerService;

    @Mock
    private AttractionMapper attractionMapper;

    @Mock
    private AttractionSyncService attractionSyncService;

    @Mock
    private AmapTransitClient amapTransitClient;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private VectorCandidateRetrieverService vectorCandidateRetrieverService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private AiRoutePlanningServiceImpl service;

    @Test
    void submittedRouteOptimizationCanAddAndDeletePois() throws Exception {
        RouteAttraction first = routeNode("POI-1", 1);
        RouteAttraction second = routeNode("POI-2", 2);
        when(attractionMapper.selectByPoiIds(List.of("POI-1", "POI-2")))
                .thenReturn(List.of(attraction("POI-1", "景点一"), attraction("POI-2", "景点二")));
        when(attractionMapper.selectByRegionCode("320100")).thenReturn(List.of(attraction("POI-3", "景点三")));
        when(vectorCandidateRetrieverService.retrieveCandidatesWithTexts(anyString(), anyInt(), anyInt()))
                .thenReturn(new VectorRetrievalResult(List.of(), List.of()));
        when(amapTransitClient.buildUndirectedMatrix(anyList())).thenReturn(List.of());
        when(routeComposerService.chat(anyString())).thenReturn("""
                {"tag":"历史人文","items":[
                  {"poiId":"POI-3","visitOrder":1,"visitTime":"2026-08-01T09:00:00","recommendedDuration":90,"notes":"乘坐地铁前往下一个景点"},
                  {"poiId":"POI-1","visitOrder":2,"visitTime":"2026-08-01T11:00:00","recommendedDuration":120,"notes":"路线结束"}
                ]}
                """);

        AIRoutePlan result = service.optimizeSubmittedRoute(List.of(first, second), "减少折返，并去掉一个景点");

        assertEquals(List.of("POI-3", "POI-1"), result.getItems().stream().map(AIRouteItem::getPoiId).toList());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(routeComposerService).chat(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("允许增删"));
        assertTrue(promptCaptor.getValue().contains("POI-3"));
        assertTrue(promptCaptor.getValue().contains("[当前路线]"));
        assertTrue(promptCaptor.getValue().contains("减少折返"));
    }

    @Test
    void submittedRouteOptimizationAcceptsSingleAttractionAndMayAdd() throws Exception {
        RouteAttraction only = routeNode("POI-1", 1);
        when(attractionMapper.selectByPoiIds(List.of("POI-1")))
                .thenReturn(List.of(attraction("POI-1", "景点一")));
        when(attractionMapper.selectByRegionCode("320100")).thenReturn(List.of(attraction("POI-2", "景点二")));
        when(vectorCandidateRetrieverService.retrieveCandidatesWithTexts(anyString(), anyInt(), anyInt()))
                .thenReturn(new VectorRetrievalResult(List.of(), List.of()));
        when(amapTransitClient.buildUndirectedMatrix(anyList())).thenReturn(List.of());
        when(routeComposerService.chat(anyString())).thenReturn("""
                {"tag":"历史人文","items":[
                  {"poiId":"POI-1","visitOrder":1,"visitTime":"2026-08-01T09:00:00","recommendedDuration":90,"notes":"乘坐地铁前往下一个景点"},
                  {"poiId":"POI-2","visitOrder":2,"visitTime":"2026-08-01T11:00:00","recommendedDuration":120,"notes":"路线结束"}
                ]}
                """);

        AIRoutePlan result = service.optimizeSubmittedRoute(List.of(only), "帮我加一个景点");

        assertEquals(List.of("POI-1", "POI-2"), result.getItems().stream().map(AIRouteItem::getPoiId).toList());
    }

    @Test
    void submittedRouteOptimizationSyncsUnknownAmapPoiAutomatically() throws Exception {
        RouteAttraction known = routeNode("POI-1", 1);
        RouteAttraction unknown = new RouteAttraction();
        unknown.setPoiId("AMAP-X");
        unknown.setVisitOrder(2);
        unknown.setRecommendedDuration(60);
        unknown.setName("夫子庙");
        unknown.setLocation("118.7888,32.0200");
        unknown.setAdcode("320104");
        unknown.setCitycode("025");
        unknown.setCityname("南京市");
        unknown.setPname("江苏省");

        when(attractionMapper.selectByPoiIds(anyList())).thenReturn(List.of(attraction("POI-1", "景点一")));
        when(attractionMapper.selectByRegionCode("320100")).thenReturn(List.of());
        when(vectorCandidateRetrieverService.retrieveCandidatesWithTexts(anyString(), anyInt(), anyInt()))
                .thenReturn(new VectorRetrievalResult(List.of(), List.of()));
        Attraction synced = new Attraction();
        synced.setPoiId("AMAP-X");
        synced.setName("夫子庙");
        synced.setLocation("118.7888,32.0200");
        synced.setAdcode("320104");
        synced.setCitycode("025");
        synced.setStatus("ACTIVE");
        when(attractionSyncService.syncFromAmap("AMAP-X")).thenReturn(synced);
        when(amapTransitClient.buildUndirectedMatrix(anyList())).thenReturn(List.of());
        when(routeComposerService.chat(anyString())).thenReturn("""
                {"tag":"历史人文","items":[
                  {"poiId":"POI-1","visitOrder":1,"visitTime":"2026-08-01T09:00:00","recommendedDuration":90,"notes":"乘坐地铁前往下一个景点"},
                  {"poiId":"AMAP-X","visitOrder":2,"visitTime":"2026-08-01T11:00:00","recommendedDuration":120,"notes":"路线结束"}
                ]}
                """);

        AIRoutePlan result = service.optimizeSubmittedRoute(List.of(known, unknown), null);

        assertEquals(List.of("POI-1", "AMAP-X"), result.getItems().stream().map(AIRouteItem::getPoiId).toList());
        verify(attractionSyncService).syncFromAmap("AMAP-X");
    }

    @Test
    void submittedRouteOptimizationFallsBackToRequestMetadataWhenAmapSyncFails() throws Exception {
        RouteAttraction known = routeNode("POI-1", 1);
        RouteAttraction unknown = new RouteAttraction();
        unknown.setPoiId("AMAP-X");
        unknown.setVisitOrder(2);
        unknown.setRecommendedDuration(60);
        unknown.setName("夫子庙");
        unknown.setLocation("118.7888,32.0200");
        unknown.setAdcode("320104");

        when(attractionMapper.selectByPoiIds(anyList())).thenReturn(List.of(attraction("POI-1", "景点一")));
        when(attractionMapper.selectByRegionCode("320100")).thenReturn(List.of());
        when(vectorCandidateRetrieverService.retrieveCandidatesWithTexts(anyString(), anyInt(), anyInt()))
                .thenReturn(new VectorRetrievalResult(List.of(), List.of()));
        when(attractionSyncService.syncFromAmap("AMAP-X")).thenThrow(new IllegalArgumentException("高德不可用"));
        when(amapTransitClient.buildUndirectedMatrix(anyList())).thenReturn(List.of());
        when(routeComposerService.chat(anyString())).thenReturn("""
                {"tag":"历史人文","items":[
                  {"poiId":"POI-1","visitOrder":1,"visitTime":"2026-08-01T09:00:00","recommendedDuration":90,"notes":"乘坐地铁前往下一个景点"},
                  {"poiId":"AMAP-X","visitOrder":2,"visitTime":"2026-08-01T11:00:00","recommendedDuration":120,"notes":"路线结束"}
                ]}
                """);

        AIRoutePlan result = service.optimizeSubmittedRoute(List.of(known, unknown), null);

        assertEquals(List.of("POI-1", "AMAP-X"), result.getItems().stream().map(AIRouteItem::getPoiId).toList());
        ArgumentCaptor<Attraction> captor = ArgumentCaptor.forClass(Attraction.class);
        verify(attractionMapper).upsert(captor.capture());
        Attraction registered = captor.getValue();
        assertEquals("AMAP-X", registered.getPoiId());
        assertEquals("夫子庙", registered.getName());
        assertEquals("118.7888,32.0200", registered.getLocation());
        assertEquals("ACTIVE", registered.getStatus());
    }

    @Test
    void submittedRouteOptimizationRejectsPoiOutsideCandidateSet() {
        when(attractionMapper.selectByPoiIds(List.of("POI-1", "POI-2")))
                .thenReturn(List.of(attraction("POI-1", "景点一"), attraction("POI-2", "景点二")));
        when(vectorCandidateRetrieverService.retrieveCandidatesWithTexts(anyString(), anyInt(), anyInt()))
                .thenReturn(new VectorRetrievalResult(List.of(), List.of()));
        when(amapTransitClient.buildUndirectedMatrix(anyList())).thenReturn(List.of());
        when(routeComposerService.chat(anyString())).thenReturn("""
                {"tag":"历史人文","items":[
                  {"poiId":"POI-X","visitOrder":1,"visitTime":"2026-08-01T09:00:00","recommendedDuration":90,"notes":"路线结束"}
                ]}
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.optimizeSubmittedRoute(
                        List.of(routeNode("POI-1", 1), routeNode("POI-2", 2)),
                        null
                )
        );

        assertTrue(exception.getMessage().contains("outside candidate set"));
    }

    @Test
    void submittedRouteOptimizationRejectsEmptyAiResult() {
        when(attractionMapper.selectByPoiIds(List.of("POI-1", "POI-2")))
                .thenReturn(List.of(attraction("POI-1", "景点一"), attraction("POI-2", "景点二")));
        when(vectorCandidateRetrieverService.retrieveCandidatesWithTexts(anyString(), anyInt(), anyInt()))
                .thenReturn(new VectorRetrievalResult(List.of(), List.of()));
        when(amapTransitClient.buildUndirectedMatrix(anyList())).thenReturn(List.of());
        when(routeComposerService.chat(anyString())).thenReturn("""
                {"tag":"历史人文","items":[]}
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.optimizeSubmittedRoute(
                        List.of(routeNode("POI-1", 1), routeNode("POI-2", 2)),
                        null
                )
        );

        assertTrue(exception.getMessage().contains("empty"));
    }

    @Test
    void automaticCandidatesAreCappedAtTwelveWithoutDroppingSubmittedPois() throws Exception {
        RouteAttraction submitted = routeNode("POI-1", 1);
        List<String> retrievedPoiIds = IntStream.rangeClosed(2, 20)
                .mapToObj(index -> "POI-" + index)
                .toList();
        List<Attraction> retrievedAttractions = retrievedPoiIds.stream()
                .map(poiId -> attraction(poiId, "景点" + poiId))
                .toList();

        when(attractionMapper.selectByPoiIds(List.of("POI-1")))
                .thenReturn(List.of(attraction("POI-1", "已提交景点")));
        when(vectorCandidateRetrieverService.retrieveCandidatesWithTexts(anyString(), anyInt(), anyInt()))
                .thenReturn(new VectorRetrievalResult(retrievedPoiIds, List.of()));
        when(attractionMapper.selectActiveByPoiIds(retrievedPoiIds)).thenReturn(retrievedAttractions);
        when(attractionMapper.selectByRegionCode("320100")).thenReturn(List.of());
        when(amapTransitClient.buildUndirectedMatrix(anyList())).thenReturn(List.of());
        when(routeComposerService.chat(anyString())).thenReturn("""
                {"tag":"历史人文","items":[
                  {"poiId":"POI-1","visitOrder":1,"visitTime":"2026-08-01T09:00:00","recommendedDuration":90,"notes":"路线结束"}
                ]}
                """);

        service.optimizeSubmittedRoute(List.of(submitted), "减少折返");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Attraction>> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(amapTransitClient).buildUndirectedMatrix(candidatesCaptor.capture());
        assertEquals(12, candidatesCaptor.getValue().size());
        assertEquals("POI-1", candidatesCaptor.getValue().get(0).getPoiId());
    }

    private RouteAttraction routeNode(String poiId, int visitOrder) {
        RouteAttraction node = new RouteAttraction();
        node.setPoiId(poiId);
        node.setVisitOrder(visitOrder);
        node.setRecommendedDuration(60);
        return node;
    }

    private Attraction attraction(String poiId, String name) {
        Attraction attraction = new Attraction();
        attraction.setPoiId(poiId);
        attraction.setName(name);
        attraction.setAdcode("320100");
        attraction.setLocation("118.0,32.0");
        return attraction;
    }
}
