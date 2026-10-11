package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.mapper.RouteMapper;
import com.viyangle.study_tour.mapper.RouteAttractionMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.Route;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.service.VectorCandidateRetrieverService;
import com.viyangle.study_tour.utils.AiConversationScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiConversationIsolationTest {
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void differentAccountsUsingSameMemoryCannotOverwriteEachOthersRoute() {
        RouteServiceImpl service = new RouteServiceImpl();
        RouteMapper routes = mock(RouteMapper.class);
        RouteAttractionMapper nodes = mock(RouteAttractionMapper.class);
        AttractionMapper attractions = mock(AttractionMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        Map<String, String> stored = new HashMap<>();
        // Legacy unowned data is deliberately not used to select a writable route.
        stored.put("ai:route:memory:shared", "999");
        stored.put("ai:route:memory:account:1:shared", "999");
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(invocation -> stored.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            stored.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        ReflectionTestUtils.setField(service, "routeMapper", routes);
        ReflectionTestUtils.setField(service, "routeAttractionMapper", nodes);
        ReflectionTestUtils.setField(service, "attractionMapper", attractions);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        Attraction attraction = new Attraction();
        attraction.setPoiId("POI-1");
        attraction.setAdcode("320100");
        when(attractions.selectByPoiId("POI-1")).thenReturn(attraction);
        when(attractions.selectByPoiIds(List.of("POI-1"))).thenReturn(List.of(attraction));
        AtomicLong ids = new AtomicLong(100);
        doAnswer(invocation -> {
            invocation.<Route>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        }).when(routes).insert(any(Route.class));
        RouteAttraction node = new RouteAttraction();
        node.setPoiId("POI-1");

        authenticate(1L);
        Long first = service.saveOrUpdateAIConversationRoute("shared", "历史人文", List.of(node));
        authenticate(2L);
        Long second = service.saveOrUpdateAIConversationRoute("shared", "历史人文", List.of(node));

        assertNotEquals(first, second);
        verify(routes, times(2)).insert(any(Route.class));
        verify(routes, never()).updateById(any());
        verify(values, never()).get("ai:route:memory:shared");
        verify(values, never()).get("ai:route:memory:account:1:shared");
        verify(routes, never()).selectById(999L);
        assertTrue(stored.keySet().stream().anyMatch(key -> key.endsWith("account:1:shared")));
        assertTrue(stored.keySet().stream().anyMatch(key -> key.endsWith("account:2:shared")));

        // The owner's next request updates its own existing route, not the other account's.
        Route existing = new Route();
        existing.setId(first);
        when(routes.selectById(first)).thenReturn(existing);
        authenticate(1L);
        assertEquals(first, service.saveOrUpdateAIConversationRoute("shared", "历史人文", List.of(node)));
        var update = org.mockito.ArgumentCaptor.forClass(Route.class);
        verify(routes).updateById(update.capture());
        assertEquals(first, update.getValue().getId());
    }

    @Test
    void planningReadsOnlyCurrentAccountsConstraintHistory() {
        AiRoutePlanningServiceImpl service = new AiRoutePlanningServiceImpl();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        VectorCandidateRetrieverService retriever = mock(VectorCandidateRetrieverService.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("ai:route:v2:constraint:account:1:shared"))
                .thenReturn("{\"userMessages\":[\"账号A私有历史\"]}");
        // Stop after state loading, before any graph/model/external-service operation.
        IllegalStateException stop = new IllegalStateException("retrieval stopped for test");
        when(retriever.retrieveCandidatesWithTexts(anyString(), anyInt(), anyInt())).thenThrow(stop);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "vectorCandidateRetrieverService", retriever);

        authenticate(1L);
        assertSame(stop, assertThrows(IllegalStateException.class,
                () -> service.planRouteV2("shared", "安排路线", null)));
        authenticate(2L);
        assertSame(stop, assertThrows(IllegalStateException.class,
                () -> service.planRouteV2("shared", "安排路线", null)));

        verify(values).get("ai:route:v2:constraint:account:1:shared");
        verify(values).get("ai:route:v2:constraint:account:2:shared");
        verify(values, never()).get("ai:route:constraint:shared");
        verify(values, never()).get("ai:route:constraint:account:1:shared");
        var queries = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(retriever, times(2)).retrieveCandidatesWithTexts(queries.capture(), anyInt(), anyInt());
        assertTrue(queries.getAllValues().get(0).contains("账号A私有历史"));
        assertFalse(queries.getAllValues().get(1).contains("账号A私有历史"));
    }

    @Test
    void serviceRejectsImpersonationAndUnauthenticatedSessionWrites() {
        authenticate(1L);
        assertThrows(ForbiddenException.class,
                () -> new AiRoutePlanningServiceImpl().planRouteV2("shared", "路线", 2L));
        SecurityContextHolder.clearContext();
        assertThrows(UnauthorizedException.class,
                () -> new RouteServiceImpl().saveOrUpdateAIConversationRoute("shared", "历史人文", List.of()));
    }

    @Test
    void accountNamespaceCannotBeInjectedUsingMemoryId() {
        authenticate(1L);
        String first = AiConversationScope.storageId("account:2:shared");
        authenticate(2L);
        String second = AiConversationScope.storageId("shared");
        assertNotEquals(first, second);
    }

    private void authenticate(Long id) {
        var authentication = new UsernamePasswordAuthenticationToken(id.toString(), null, List.of());
        authentication.setDetails(Map.of("accountId", id, "role", "USER"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
