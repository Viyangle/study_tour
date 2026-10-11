package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.mapper.RouteAttractionMapper;
import com.viyangle.study_tour.mapper.RouteFavoriteMapper;
import com.viyangle.study_tour.mapper.RouteMapper;
import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.Route;
import com.viyangle.study_tour.pojo.RouteAttraction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteServiceImplTest {

    @Mock
    private RouteMapper routeMapper;

    @Mock
    private RouteAttractionMapper routeAttractionMapper;

    @Mock
    private RouteFavoriteMapper routeFavoriteMapper;

    @Mock
    private AttractionMapper attractionMapper;

    @InjectMocks
    private RouteServiceImpl routeService;

    @Test
    void manualRouteRefreshesOutdatedAttractionFlagAfterNodesAreSaved() {
        Attraction attraction = new Attraction();
        attraction.setPoiId("POI-1");
        attraction.setAdcode("320100");
        when(attractionMapper.selectByPoiId("POI-1")).thenReturn(attraction);
        when(attractionMapper.selectByPoiIds(List.of("POI-1"))).thenReturn(List.of(attraction));
        doAnswer(invocation -> {
            invocation.<Route>getArgument(0).setId(42L);
            return 1;
        }).when(routeMapper).insert(any(Route.class));

        RouteAttraction node = new RouteAttraction();
        node.setPoiId("POI-1");

        Long routeId = routeService.generateRouteByManual(List.of(node));

        assertEquals(42L, routeId);
        assertEquals(1, node.getVisitOrder());
        InOrder order = inOrder(routeMapper, routeAttractionMapper);
        order.verify(routeMapper).insert(any(Route.class));
        order.verify(routeAttractionMapper).insert(node);
        order.verify(routeMapper).refreshOutdatedAttractionFlagById(42L);
        verify(attractionMapper).selectByPoiIds(List.of("POI-1"));
    }

    @Test
    void optimizedRoutePersistsTheAiSelectedTag() {
        Attraction attraction = new Attraction();
        attraction.setPoiId("POI-1");
        attraction.setAdcode("320100");
        when(attractionMapper.selectByPoiId("POI-1")).thenReturn(attraction);
        when(attractionMapper.selectByPoiIds(List.of("POI-1"))).thenReturn(List.of(attraction));
        doAnswer(invocation -> {
            invocation.<Route>getArgument(0).setId(43L);
            return 1;
        }).when(routeMapper).insert(any(Route.class));
        RouteAttraction node = new RouteAttraction();
        node.setPoiId("POI-1");

        Long routeId = routeService.saveOptimizedRoute("历史人文", List.of(node));

        ArgumentCaptor<Route> routeCaptor = ArgumentCaptor.forClass(Route.class);
        verify(routeMapper).insert(routeCaptor.capture());
        assertEquals(43L, routeId);
        assertEquals("历史人文", routeCaptor.getValue().getTag());
    }

    @Test
    void userOrLeaderCanFavoriteAnExistingRoute() {
        Route route = new Route();
        route.setId(42L);
        when(routeMapper.selectById(42L)).thenReturn(route);

        routeService.addFavorite(42L, 10L);

        verify(routeFavoriteMapper).insertIfAbsent(10L, 42L);
    }

    @Test
    void nonexistentRouteCannotBeFavorited() {
        when(routeMapper.selectById(404L)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class,
                () -> routeService.addFavorite(404L, 10L));
    }

    @Test
    void removingMissingFavoriteIsIdempotent() {
        routeService.removeFavorite(42L, 10L);

        verify(routeFavoriteMapper).deleteByAccountIdAndRouteId(10L, 42L);
    }
}
