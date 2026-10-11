package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.service.AiRoutePlanningService;
import com.viyangle.study_tour.service.ProjectService;
import com.viyangle.study_tour.service.RouteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteControllerTest {

    @Mock
    private RouteService routeService;

    @Mock
    private ProjectService projectService;

    @Mock
    private AiRoutePlanningService aiRoutePlanningService;

    @InjectMocks
    private RouteController routeController;

    @BeforeEach
    void authenticate() {
        var authentication = new UsernamePasswordAuthenticationToken("7", null, List.of());
        authentication.setDetails(Map.of("accountId", 7L, "role", "USER"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aiCannotUseAnotherAccountsPreferences() {
        assertThrows(ForbiddenException.class,
                () -> routeController.generateRouteByAIV2("memory-1", "安排路线", 8L, null));
        verifyNoInteractions(aiRoutePlanningService, routeService);
    }

    @Test
    void aiRequiresAuthenticationBeforeAnyModelCall() {
        SecurityContextHolder.clearContext();
        assertThrows(UnauthorizedException.class,
                () -> routeController.generateRouteByAIV2("memory-1", "安排路线", null, null));
        verifyNoInteractions(aiRoutePlanningService, routeService);
    }

    @Test
    void aiRejectsInvalidMemoryBeforeAnyModelCall() {
        assertThrows(IllegalArgumentException.class,
                () -> routeController.generateRouteByAIV2(" ", "安排路线", null, null));
        verifyNoInteractions(aiRoutePlanningService, routeService);
    }

    @Test
    void publicRouteListingRetainsExistingPreferenceParameter() {
        when(routeService.getPagedRoutesByPreference(8L, 1, 10)).thenReturn(List.of());
        assertEquals(List.of(), routeController.getAllRoutes(1, 10, 8L).getData());
        verify(routeService).getPagedRoutesByPreference(8L, 1, 10);
        verifyNoInteractions(aiRoutePlanningService);
    }

    @Test
    void manualRouteIsSavedWithoutAiOptimization() throws Exception {
        RouteAttraction attraction = new RouteAttraction();
        attraction.setPoiId("POI-1");
        when(routeService.generateRouteByManual(List.of(attraction))).thenReturn(12L);

        Result result = routeController.generateRouteByManual(List.of(attraction));

        assertEquals(12L, result.getData());
        verify(routeService).generateRouteByManual(List.of(attraction));
        verify(aiRoutePlanningService, never()).optimizeSubmittedRoute(anyList(), eq(""));
    }

    @Test
    void languageOnlyAiRouteIsGeneratedThenOptimizedBeforeItIsSaved() throws Exception {
        AIRoutePlan generated = new AIRoutePlan("历史人文", List.of(
                new AIRouteItem("POI-1", 1, "2026-04-01T09:00:00", 120, "初始路线")
        ));
        AIRoutePlan optimized = new AIRoutePlan("博物馆研学", List.of(
                new AIRouteItem("POI-2", 1, "2026-04-01T10:00:00", 90, "步行前往")
        ));
        when(aiRoutePlanningService.planRouteV2("memory-1", "安排一天研学", 7L)).thenReturn(generated);
        when(aiRoutePlanningService.optimizeSubmittedRoute(anyList(), eq("安排一天研学"))).thenReturn(optimized);
        when(routeService.saveOrUpdateAIConversationRoute(eq("memory-1"), eq("博物馆研学"), anyList()))
                .thenReturn(88L);

        Result result = routeController.generateRouteByAIV2("memory-1", "安排一天研学", 7L, null);

        assertEquals(88L, result.getData());
        verify(aiRoutePlanningService).planRouteV2("memory-1", "安排一天研学", 7L);
        verify(aiRoutePlanningService).optimizeSubmittedRoute(anyList(), eq("安排一天研学"));
        verify(routeService).saveOrUpdateAIConversationRoute(eq("memory-1"), eq("博物馆研学"), anyList());
    }

    @Test
    void submittedAttractionsAreOptimizedWithoutRegeneratingTheRoute() throws Exception {
        RouteAttraction submitted = new RouteAttraction();
        submitted.setPoiId("POI-1");
        AIRoutePlan optimized = new AIRoutePlan("历史人文", List.of(
                new AIRouteItem("POI-1", 1, "2026-04-01T09:00:00", 120, "步行前往")
        ));
        when(aiRoutePlanningService.optimizeSubmittedRoute(List.of(submitted), "减少折返")).thenReturn(optimized);
        when(routeService.saveOrUpdateAIConversationRoute(eq("memory-2"), eq("历史人文"), anyList()))
                .thenReturn(89L);

        Result result = routeController.generateRouteByAIV2(
                "memory-2", "减少折返", null, List.of(submitted));

        assertEquals(89L, result.getData());
        verify(aiRoutePlanningService).optimizeSubmittedRoute(List.of(submitted), "减少折返");
        verify(aiRoutePlanningService, never()).planRouteV2(eq("memory-2"), eq("减少折返"), eq(null));
        verify(routeService).saveOrUpdateAIConversationRoute(eq("memory-2"), eq("历史人文"), anyList());
    }
}
