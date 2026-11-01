package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.RouteAttractionMapper;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.pojo.TagDimension;
import com.viyangle.study_tour.pojo.TagFeedbackEvent;
import com.viyangle.study_tour.service.TagFeedbackService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouteIterationFeedbackServiceTest {

    @Mock private TagFeedbackService tagFeedbackService;
    @Mock private TagVectorService tagVectorService;
    @Mock private RouteAttractionMapper routeAttractionMapper;

    @InjectMocks
    private RouteIterationFeedbackService service;

    @BeforeEach
    void setUp() {
        when(tagVectorService.isReady()).thenReturn(true);
        when(tagVectorService.dimensions()).thenReturn(dimensions());
        when(tagFeedbackService.record(any())).thenReturn(1);
    }

    private static List<TagDimension> dimensions() {
        List<TagDimension> result = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            TagDimension dimension = new TagDimension();
            dimension.setDimVersion(1);
            dimension.setOrdinal(i);
            dimension.setTagId((long) i);
            dimension.setCode("DIM_" + i);
            dimension.setName(i == 1 ? "历史人文" : "维度" + i);
            dimension.setEnabled(true);
            result.add(dimension);
        }
        return result;
    }

    private static AIRoutePlan plan(String tag, String... poiIds) {
        AIRoutePlan plan = new AIRoutePlan();
        plan.setTag(tag);
        List<AIRouteItem> items = new ArrayList<>();
        for (int i = 0; i < poiIds.length; i++) {
            AIRouteItem item = new AIRouteItem();
            item.setPoiId(poiIds[i]);
            item.setVisitOrder(i + 1);
            items.add(item);
        }
        plan.setItems(items);
        return plan;
    }

    @Test
    @DisplayName("首轮只记录会话，不产生反馈事件")
    void firstPlanOnlyStoresSession() {
        service.recordPlan(1L, "m1", plan("历史人文", "p1", "p2"));

        verify(tagFeedbackService, never()).record(any());
    }

    @Test
    @DisplayName("中间轮次只累积出现过景点，不提前加分或减分")
    void intermediatePlansDoNotRecordEvidence() {
        service.recordPlan(1L, "m1", plan("历史人文", "p1", "p2"));
        service.recordPlan(1L, "m1", plan("历史人文", "p1", "p3"));

        verify(tagFeedbackService, never()).record(any());
    }

    @Test
    @DisplayName("最终结算：最终路线正向，过程出现但未进最终路线轻降")
    void finalizeConfirmsFinalAndRejectsSeenButUnused() {
        service.recordPlan(1L, "m1", plan("历史人文", "p1", "p2"));
        service.recordPlan(1L, "m1", plan("历史人文", "p1", "p3"));

        service.finalizeSession(1L, "m1", List.of("p1", "p3"),
                Map.of("历史人文", 1.0), "历史人文");

        ArgumentCaptor<TagFeedbackEvent> captor = ArgumentCaptor.forClass(TagFeedbackEvent.class);
        verify(tagFeedbackService, times(3)).record(captor.capture());
        List<TagFeedbackEvent> events = captor.getAllValues();
        assertEquals(2, events.stream().filter(e -> "ROUTE_STOP_CONFIRM".equals(e.getEventType())).count());
        assertEquals(1, events.stream().filter(e -> "ROUTE_STOP_REJECT".equals(e.getEventType())).count());

        TagFeedbackEvent confirm = events.stream()
                .filter(e -> "ROUTE_STOP_CONFIRM".equals(e.getEventType())).findFirst().orElseThrow();
        TagFeedbackEvent reject = events.stream()
                .filter(e -> "ROUTE_STOP_REJECT".equals(e.getEventType())).findFirst().orElseThrow();
        assertEquals(0.8, confirm.getEventValue(), 1e-9);
        assertEquals(-0.2, reject.getEventValue(), 1e-9);
        assertTrue(confirm.getIntentVector().contains("1.0"));
    }

    @Test
    @DisplayName("候选曝光但始终没选：结算时给 -0.02 轻降")
    void exposedButNeverSelectedGetsSmallNegative() {
        when(tagVectorService.score(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1.0);
        service.recordPlan(1L, "m1", plan("历史人文", "p1", "p2"));
        service.recordExposure(1L, "m1", List.of("p1", "p3", "p4"),
                Map.of("历史人文", 1.0), "历史人文");

        service.finalizeSession(1L, "m1", List.of("p1"),
                Map.of("历史人文", 1.0), "历史人文");

        ArgumentCaptor<TagFeedbackEvent> captor = ArgumentCaptor.forClass(TagFeedbackEvent.class);
        verify(tagFeedbackService, times(4)).record(captor.capture());
        List<TagFeedbackEvent> events = captor.getAllValues();
        assertEquals(1, events.stream().filter(e -> "ROUTE_STOP_CONFIRM".equals(e.getEventType())).count());
        assertEquals(1, events.stream().filter(e -> "ROUTE_STOP_REJECT".equals(e.getEventType())).count());
        assertEquals(2, events.stream().filter(e -> "ROUTE_STOP_NOT_SELECTED".equals(e.getEventType())).count());
        assertTrue(events.stream().filter(e -> "ROUTE_STOP_NOT_SELECTED".equals(e.getEventType()))
                .allMatch(e -> Math.abs(e.getEventValue() + 0.02) < 1e-9));
    }

    @Test
    @DisplayName("项目发布时按 routeId 找到会话并结算")
    void finalizeByRouteIdUsesSessionBinding() {
        service.recordPlan(1L, "m1", plan("历史人文", "p1", "p2"));
        service.bindRoute(10L, 1L, "m1");

        RouteAttraction stop = new RouteAttraction();
        stop.setPoiId("p1");
        when(routeAttractionMapper.selectByRouteId(10L)).thenReturn(List.of(stop));

        service.finalizeByRouteId(10L);

        ArgumentCaptor<TagFeedbackEvent> captor = ArgumentCaptor.forClass(TagFeedbackEvent.class);
        verify(tagFeedbackService, times(2)).record(captor.capture());
        assertEquals(1, captor.getAllValues().stream()
                .filter(e -> "ROUTE_STOP_CONFIRM".equals(e.getEventType())).count());
        assertEquals(1, captor.getAllValues().stream()
                .filter(e -> "ROUTE_STOP_REJECT".equals(e.getEventType())).count());
    }

    @Test
    @DisplayName("相同提案重复生成不重复计权")
    void identicalPlanDoesNotRecordAgain() {
        service.recordPlan(1L, "m1", plan("历史人文", "p1", "p2"));
        service.recordPlan(1L, "m1", plan("历史人文", "p1", "p2"));

        verify(tagFeedbackService, never()).record(any());
    }

    @Test
    @DisplayName("没有 tag 意图时不建立会话，避免按账号偏好误归因")
    void planWithoutIntentIsSkipped() {
        service.recordPlan(1L, "m1", plan(null, "p1"));
        service.finalizeSession(1L, "m1", List.of("p1"), null, null);

        verify(tagFeedbackService, never()).record(any());
    }
}
