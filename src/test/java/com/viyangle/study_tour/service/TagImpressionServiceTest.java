package com.viyangle.study_tour.service;

import com.viyangle.study_tour.mapper.TagImpressionMapper;
import com.viyangle.study_tour.pojo.TagImpression;
import com.viyangle.study_tour.pojo.TagPopularity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagImpressionServiceTest {

    @Mock
    private TagImpressionMapper impressionMapper;

    @InjectMocks
    private TagImpressionService service;

    @Test
    @DisplayName("曝光按传入顺序编号并去重")
    void recordsDeduplicatedImpressionsWithPositions() {
        service.record(1L, "s1", "AI_PLAN", List.of("p1", "p2", "p1"), "v1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TagImpression>> captor = ArgumentCaptor.forClass(List.class);
        verify(impressionMapper).batchInsert(captor.capture());
        assertEquals(2, captor.getValue().size());
        assertEquals(1, captor.getValue().get(0).getPosition());
        assertEquals(2, captor.getValue().get(1).getPosition());
    }

    @Test
    @DisplayName("热度按曝光去重用户数归一化到 0~1")
    void normalizesPopularityByMaxUsers() {
        when(impressionMapper.selectPopularitySince(any())).thenReturn(List.of(
                new TagPopularity("p1", 9),
                new TagPopularity("p2", 1)));
        service.refreshPopularity();

        assertEquals(1.0, service.popularity("p1"), 1e-9);
        assertEquals(Math.log1p(1) / Math.log1p(9), service.popularity("p2"), 1e-9);
        assertEquals(0.0, service.popularity("unknown"), 1e-9);
    }
}
