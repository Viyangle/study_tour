package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AttractionGuideMapper;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.AttractionGuide;
import com.viyangle.study_tour.pojo.AttractionGuideSource;
import com.viyangle.study_tour.service.AttractionGuideSourceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttractionGuideSyncServiceImplTest {

    @Mock
    private AttractionGuideMapper guideMapper;

    @Mock
    private AttractionMapper attractionMapper;

    @Mock
    private AttractionGuideSourceClient sourceClient;

    @Mock
    private AttractionGuideContentGenerator contentGenerator;

    private AttractionGuideSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AttractionGuideSyncServiceImpl(
                guideMapper, attractionMapper, List.of(sourceClient), contentGenerator);
    }

    @Test
    void syncGuide_skipsManuallyMaintainedGuide() {
        AttractionGuide existing = new AttractionGuide();
        existing.setPoiId("POI-1");
        existing.setAutoUpdate(false);
        when(guideMapper.selectByPoiId("POI-1")).thenReturn(existing);

        assertThat(service.syncGuide(attraction("POI-1", null))).isFalse();

        verifyNoInteractions(sourceClient, contentGenerator);
        verify(guideMapper, never()).upsert(any());
    }

    @Test
    void syncGuide_persistsSourcedContentAndMetadata() {
        Attraction attraction = attraction("POI-1", null);
        AttractionGuideSource source = source("WIKIPEDIA", false, "南京景点介绍正文");
        AttractionGuide generated = generated("简介", "讲解");
        when(guideMapper.selectByPoiId("POI-1")).thenReturn(null);
        when(sourceClient.fetch(attraction)).thenReturn(Optional.of(source));
        when(contentGenerator.usingAi()).thenReturn(false);
        when(contentGenerator.generate(attraction, source, null)).thenReturn(generated);
        when(guideMapper.upsert(any(AttractionGuide.class))).thenReturn(1);

        assertThat(service.syncGuide(attraction)).isTrue();

        ArgumentCaptor<AttractionGuide> captor = ArgumentCaptor.forClass(AttractionGuide.class);
        verify(guideMapper).upsert(captor.capture());
        AttractionGuide saved = captor.getValue();
        assertThat(saved.getPoiId()).isEqualTo("POI-1");
        assertThat(saved.getSummary()).isEqualTo("简介");
        assertThat(saved.getSourceType()).isEqualTo("WIKIPEDIA");
        assertThat(saved.getSourceRevision()).isEqualTo("12345");
        assertThat(saved.getSourceLicense()).isEqualTo("CC BY-SA 4.0");
        assertThat(saved.getSyncStatus()).isEqualTo("SOURCED");
        assertThat(saved.getAutoUpdate()).isTrue();
        assertThat(saved.getStatus()).isEqualTo("PUBLISHED");
        assertThat(saved.getSourceHash()).hasSize(64);
        assertThat(saved.getSyncedAt()).isNotNull();
    }

    @Test
    void syncGuide_usesParentSourceWhenPointHasNoIndependentArticle() {
        Attraction child = attraction("CHILD", "PARENT");
        Attraction parent = attraction("PARENT", null);
        parent.setName("所属景区");
        AttractionGuideSource parentSource = source("WIKIPEDIA", false, "所属景区背景资料");
        when(guideMapper.selectByPoiId("CHILD")).thenReturn(null);
        when(attractionMapper.selectByPoiId("PARENT")).thenReturn(parent);
        when(sourceClient.fetch(child)).thenReturn(Optional.empty());
        when(sourceClient.fetch(parent)).thenReturn(Optional.of(parentSource));
        when(contentGenerator.usingAi()).thenReturn(false);
        when(contentGenerator.generate(any(), any(), any())).thenReturn(generated("简介", "讲解"));
        when(guideMapper.upsert(any(AttractionGuide.class))).thenReturn(1);

        assertThat(service.syncGuide(child)).isTrue();

        ArgumentCaptor<AttractionGuide> captor = ArgumentCaptor.forClass(AttractionGuide.class);
        verify(guideMapper).upsert(captor.capture());
        assertThat(captor.getValue().getSourceType()).isEqualTo("PARENT");
        assertThat(captor.getValue().getSyncStatus()).isEqualTo("PARENT");
        assertThat(captor.getValue().getSourceName()).contains("所属景点：所属景区");
    }

    @Test
    void syncGuide_fallsBackToAmapFactsWithoutInventingContent() {
        Attraction attraction = attraction("POI-1", null);
        when(guideMapper.selectByPoiId("POI-1")).thenReturn(null);
        when(sourceClient.fetch(attraction)).thenReturn(Optional.empty());
        when(contentGenerator.usingAi()).thenReturn(false);
        when(contentGenerator.generate(any(), any(), any())).thenReturn(generated("基础简介", null));
        when(guideMapper.upsert(any(AttractionGuide.class))).thenReturn(1);

        assertThat(service.syncGuide(attraction)).isTrue();

        ArgumentCaptor<AttractionGuide> guideCaptor = ArgumentCaptor.forClass(AttractionGuide.class);
        ArgumentCaptor<AttractionGuideSource> sourceCaptor = ArgumentCaptor.forClass(AttractionGuideSource.class);
        verify(guideMapper).upsert(guideCaptor.capture());
        verify(contentGenerator).generate(any(), sourceCaptor.capture(), any());
        assertThat(guideCaptor.getValue().getSourceType()).isEqualTo("AMAP");
        assertThat(guideCaptor.getValue().getSyncStatus()).isEqualTo("FALLBACK");
        assertThat(sourceCaptor.getValue().getText()).contains("不得补充历史年代");
        assertThat(guideCaptor.getValue().getContent()).isNull();
    }

    @Test
    void syncGuide_skipsWhenSourceHashIsUnchanged() {
        Attraction attraction = attraction("POI-1", null);
        AttractionGuideSource source = source("WIKIPEDIA", false, "南京景点介绍正文");
        AttractionGuide generated = generated("简介", "讲解");
        when(guideMapper.selectByPoiId("POI-1")).thenReturn(null);
        when(sourceClient.fetch(attraction)).thenReturn(Optional.of(source));
        when(contentGenerator.usingAi()).thenReturn(false);
        when(contentGenerator.generate(attraction, source, null)).thenReturn(generated);
        when(guideMapper.upsert(any(AttractionGuide.class))).thenReturn(1);

        service.syncGuide(attraction);
        ArgumentCaptor<AttractionGuide> captor = ArgumentCaptor.forClass(AttractionGuide.class);
        verify(guideMapper).upsert(captor.capture());
        AttractionGuide persisted = captor.getValue();

        when(guideMapper.selectByPoiId("POI-1")).thenReturn(persisted);
        assertThat(service.syncGuide(attraction)).isFalse();
        verify(guideMapper).upsert(any(AttractionGuide.class));
    }

    private Attraction attraction(String poiId, String parentPoiId) {
        Attraction attraction = new Attraction();
        attraction.setPoiId(poiId);
        attraction.setName("景点" + poiId);
        attraction.setAddress("南京市鼓楼区示例路1号");
        attraction.setCityname("南京市");
        attraction.setParentPoiId(parentPoiId);
        return attraction;
    }

    private AttractionGuideSource source(String type, boolean parentSource, String text) {
        return AttractionGuideSource.builder()
                .sourceType(type)
                .sourceName("中文维基百科")
                .sourceUrl("https://zh.wikipedia.org/wiki/example")
                .text(text)
                .revision("12345")
                .license("CC BY-SA 4.0")
                .attribution("维基编者；AI 改写")
                .parentSource(parentSource)
                .build();
    }

    private AttractionGuide generated(String summary, String content) {
        AttractionGuide guide = new AttractionGuide();
        guide.setSummary(summary);
        guide.setContent(content);
        guide.setStudyPoints(List.of("知识点"));
        guide.setQuestions(List.of("思考题"));
        guide.setVisitTips("参观建议");
        return guide;
    }
}
