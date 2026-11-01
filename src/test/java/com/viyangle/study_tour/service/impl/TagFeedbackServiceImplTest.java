package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.TagFeedbackEventMapper;
import com.viyangle.study_tour.pojo.TagFeedbackEvent;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TagFeedbackServiceImplTest {

    @Mock
    private TagFeedbackEventMapper tagFeedbackEventMapper;

    @InjectMocks
    private TagFeedbackServiceImpl service;

    @Test
    @DisplayName("record 写入 PENDING 事件并补默认字段")
    void recordSetsPendingFields() {
        when(tagFeedbackEventMapper.insertIgnore(any())).thenReturn(1);
        TagFeedbackEvent event = new TagFeedbackEvent();
        event.setEventType("THUMBS_UP");
        event.setPoiId("p1");

        int inserted = service.record(event);

        assertEquals(1, inserted);
        assertEquals("PENDING", event.getStatus());
        assertEquals(0, event.getAttempts());
        assertNotNull(event.getOccurredAt());
    }

    @Test
    @DisplayName("路线收藏事件带默认权重与幂等键")
    void routeActionBuildsEvent() {
        when(tagFeedbackEventMapper.insertIgnore(any())).thenReturn(1);
        ArgumentCaptor<TagFeedbackEvent> captor = ArgumentCaptor.forClass(TagFeedbackEvent.class);

        service.recordRouteAction("ROUTE_FAVORITE", 10L, 7L);

        verify(tagFeedbackEventMapper).insertIgnore(captor.capture());
        TagFeedbackEvent event = captor.getValue();
        assertEquals("ROUTE_FAVORITE", event.getEventType());
        assertEquals(10L, event.getRouteId());
        assertEquals(0.5, event.getEventValue(), 1e-9);
        assertTrue(event.getIdempotencyKey().startsWith("ROUTE_FAVORITE:route:10:7:"));
    }

    @Test
    @DisplayName("评价分数映射成 -0.4 ~ +0.4，并用 reviewId 做幂等")
    void reviewActionMapsScore() {
        when(tagFeedbackEventMapper.insertIgnore(any())).thenReturn(1);
        ArgumentCaptor<TagFeedbackEvent> captor = ArgumentCaptor.forClass(TagFeedbackEvent.class);

        service.recordReview(99L, 20L, 30L, 5, 7L);
        verify(tagFeedbackEventMapper).insertIgnore(captor.capture());
        assertEquals(0.4, captor.getValue().getEventValue(), 1e-9);
        assertTrue(captor.getValue().getIdempotencyKey().contains(":99"));

        service.recordReview(100L, 20L, 30L, 1, 7L);
        verify(tagFeedbackEventMapper, org.mockito.Mockito.times(2)).insertIgnore(captor.capture());
        assertEquals(-0.4, captor.getValue().getEventValue(), 1e-9);
    }

    @Test
    @DisplayName("关闭反馈后不写事件")
    void disabledServiceDoesNothing() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertEquals(0, service.recordRouteAction("ROUTE_FAVORITE", 10L, 7L));
        verifyNoInteractions(tagFeedbackEventMapper);
    }

    @Test
    @DisplayName("未知类型不写事件")
    void unknownTypeIsIgnored() {
        assertEquals(0, service.recordRouteAction("NOT_A_TYPE", 10L, 7L));
        verify(tagFeedbackEventMapper, never()).insertIgnore(any());
    }
}
