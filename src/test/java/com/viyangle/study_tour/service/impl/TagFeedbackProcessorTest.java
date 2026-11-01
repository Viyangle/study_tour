package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.AttractionTagMapper;
import com.viyangle.study_tour.mapper.ProjectMapper;
import com.viyangle.study_tour.mapper.RouteAttractionMapper;
import com.viyangle.study_tour.mapper.TagFeedbackEventMapper;
import com.viyangle.study_tour.pojo.AttractionTag;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.pojo.TagDimension;
import com.viyangle.study_tour.pojo.TagFeedbackEvent;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TagFeedbackProcessorTest {

    @Mock private TagFeedbackEventMapper feedbackEventMapper;
    @Mock private AttractionTagMapper attractionTagMapper;
    @Mock private RouteAttractionMapper routeAttractionMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private AccountTagPrefMapper accountTagPrefMapper;
    @Mock private TagVectorService tagVectorService;

    @InjectMocks
    private TagFeedbackProcessor processor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(processor, "enabled", true);
        ReflectionTestUtils.setField(processor, "applyEnabled", true);
        ReflectionTestUtils.setField(processor, "batchSize", 100);
        when(tagVectorService.isReady()).thenReturn(true);
        when(tagVectorService.dimensions()).thenReturn(dimensions());
        when(tagVectorService.vector("p1")).thenReturn(vector(1.0));
        when(tagVectorService.vector("p2")).thenReturn(vector(0.5));
        when(accountTagPrefMapper.selectByAccountId(1L)).thenReturn(List.of());
        when(feedbackEventMapper.sumAppliedWeightByAccountAndPoiSince(eq(1L), eq("p1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0.0);
    }

    // ==================== 测试数据 ====================

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

    private static double[] vector(double first) {
        double[] vector = new double[15];
        vector[0] = first;
        return vector;
    }

    private static TagFeedbackEvent event(long id, String type, String poiId, double value) {
        TagFeedbackEvent event = new TagFeedbackEvent();
        event.setId(id);
        event.setEventType(type);
        event.setPoiId(poiId);
        event.setEventValue(value);
        event.setAttempts(0);
        return event;
    }

    private static AttractionTag row(long tagId, double pos, double neg, LocalDateTime lastDecayAt) {
        AttractionTag row = new AttractionTag();
        row.setPoiId("p1");
        row.setTagId(tagId);
        row.setPosCount(pos);
        row.setNegCount(neg);
        row.setLastDecayAt(lastDecayAt);
        return row;
    }

    private static RouteAttraction stop(String poiId) {
        RouteAttraction stop = new RouteAttraction();
        stop.setPoiId(poiId);
        return stop;
    }

    private void pending(TagFeedbackEvent event) {
        when(feedbackEventMapper.selectPending(100)).thenReturn(List.of(event));
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("正向事件把热度加到命中的维度，并刷新向量")
    void appliesPositiveEvidenceAndReloads() {
        pending(event(1L, "ROUTE_STOP_ADD", "p1", 0.6));
        when(attractionTagMapper.selectByPoiId("p1")).thenReturn(List.of());

        processor.processPending();

        verify(attractionTagMapper).upsertEvidence("p1", 1L, 0.6, 0.0);
        verify(feedbackEventMapper).markApplied(1L);
        verify(tagVectorService).reload();
    }

    @Test
    @DisplayName("负向事件写入 neg_count")
    void appliesNegativeEvidence() {
        pending(event(1L, "ROUTE_STOP_REMOVE", "p1", -0.6));
        when(attractionTagMapper.selectByPoiId("p1")).thenReturn(List.of());

        processor.processPending();

        verify(attractionTagMapper).upsertEvidence("p1", 1L, 0.0, 0.6);
    }

    @Test
    @DisplayName("应用前先按半衰期衰减旧证据")
    void decaysOldEvidenceBeforeAdding() {
        pending(event(1L, "ROUTE_STOP_ADD", "p1", 1.0));
        when(attractionTagMapper.selectByPoiId("p1"))
                .thenReturn(List.of(row(1L, 2.0, 0.0, LocalDateTime.now().minusDays(90))));

        processor.processPending();

        org.mockito.ArgumentCaptor<Double> posCaptor = org.mockito.ArgumentCaptor.forClass(Double.class);
        verify(attractionTagMapper).upsertEvidence(eq("p1"), eq(1L), posCaptor.capture(), eq(0.0));
        org.junit.jupiter.api.Assertions.assertEquals(2.0, posCaptor.getValue(), 0.01);
    }

    @Test
    @DisplayName("显式纠正只更新指定维度")
    void explicitCorrectionOnlyTargetsOneDimension() {
        TagFeedbackEvent event = event(1L, "TAG_CORRECTION", "p1", -2.0);
        event.setAccountId(1L);
        event.setTagId(2L);
        pending(event);
        when(attractionTagMapper.selectByPoiId("p1")).thenReturn(List.of());

        processor.processPending();

        verify(attractionTagMapper).upsertEvidence("p1", 2L, 0.0, 0.8);
        verify(attractionTagMapper, never()).upsertEvidence(eq("p1"), eq(1L), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("路线保存事件展开到所有站点")
    void routeSavedEventExpandsToStops() {
        TagFeedbackEvent event = event(1L, "ROUTE_SAVED", null, 0.4);
        event.setRouteId(10L);
        pending(event);
        when(routeAttractionMapper.selectByRouteId(10L)).thenReturn(List.of(stop("p1"), stop("p2")));
        when(attractionTagMapper.selectByPoiId("p1")).thenReturn(List.of());
        when(attractionTagMapper.selectByPoiId("p2")).thenReturn(List.of());

        processor.processPending();

        verify(attractionTagMapper).upsertEvidence(eq("p1"), eq(1L), anyDouble(), eq(0.0));
        verify(attractionTagMapper).upsertEvidence(eq("p2"), eq(1L), anyDouble(), eq(0.0));
    }

    @Test
    @DisplayName("最终确认事件通过路线展开到所有站点")
    void routeConfirmEventResolvesRouteStops() {
        TagFeedbackEvent event = event(1L, "ROUTE_STOP_CONFIRM", null, 0.8);
        event.setRouteId(10L);
        pending(event);
        when(routeAttractionMapper.selectByRouteId(10L)).thenReturn(List.of(stop("p1")));
        when(attractionTagMapper.selectByPoiId("p1")).thenReturn(List.of());

        processor.processPending();

        verify(attractionTagMapper).upsertEvidence(eq("p1"), eq(1L), anyDouble(), eq(0.0));
    }

    @Test
    @DisplayName("点踩只代表偏好，不修改景点 tag 权重")
    void thumbsDownIsPreferenceOnly() {
        TagFeedbackEvent event = event(1L, "THUMBS_DOWN", "p1", -0.8);
        event.setAccountId(1L);
        pending(event);

        processor.processPending();

        verify(attractionTagMapper, never()).upsertEvidence(eq("p1"), any(), anyDouble(), anyDouble());
        verify(feedbackEventMapper).markIgnored(1L, "preference-only");
        verify(tagVectorService, never()).reload();
    }

    @Test
    @DisplayName("单用户单景点超过 30 天上限时忽略事件")
    void userCapSkipsEvent() {
        TagFeedbackEvent event = event(1L, "ROUTE_STOP_ADD", "p1", 0.6);
        event.setAccountId(1L);
        pending(event);
        when(feedbackEventMapper.sumAppliedWeightByAccountAndPoiSince(eq(1L), eq("p1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(2.0);

        processor.processPending();

        verify(attractionTagMapper, never()).upsertEvidence(eq("p1"), eq(1L), anyDouble(), anyDouble());
        verify(feedbackEventMapper).markIgnored(1L, "user cap reached");
    }

    @Test
    @DisplayName("向量未就绪时不处理事件")
    void notReadySkipsBatch() {
        when(tagVectorService.isReady()).thenReturn(false);

        processor.processPending();

        verify(feedbackEventMapper, never()).selectPending(100);
    }

    @Test
    @DisplayName("处理异常时增加重试次数，超过 3 次标记忽略")
    void failureIncrementsAttempts() {
        TagFeedbackEvent event = event(1L, "ROUTE_STOP_ADD", "p1", 0.6);
        event.setAttempts(2);
        pending(event);
        when(tagVectorService.vector("p1")).thenThrow(new RuntimeException("boom"));

        processor.processPending();

        verify(feedbackEventMapper).markIgnored(1L, "failed: boom");
        verify(feedbackEventMapper, never()).markApplied(1L);
    }

    @Test
    @DisplayName("没有目标景点的属性事件标记忽略")
    void eventWithoutTargetIsIgnored() {
        pending(event(1L, "ROUTE_STOP_ADD", null, 0.6));

        processor.processPending();

        verify(feedbackEventMapper).markIgnored(1L, "no target attraction");
    }
}
