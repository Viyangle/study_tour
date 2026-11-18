package com.viyangle.study_tour.service;

import com.viyangle.study_tour.mapper.AttractionTagLearnedAccountMapper;
import com.viyangle.study_tour.mapper.AttractionTagLearnedMapper;
import com.viyangle.study_tour.pojo.AttractionTagLearned;
import com.viyangle.study_tour.pojo.TagSupportCount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagLearnedServiceTest {

    @Mock
    private AttractionTagLearnedMapper learnedMapper;

    @Mock
    private AttractionTagLearnedAccountMapper learnedAccountMapper;

    @InjectMocks
    private TagLearnedService service;

    @Test
    @DisplayName("写入前先衰减旧证据，再叠加本次 delta")
    void decaysBeforeAddingEvidence() {
        ReflectionTestUtils.setField(service, "posHalfLifeDays", 90.0);
        AttractionTagLearned row = new AttractionTagLearned();
        row.setPoiId("p1");
        row.setTagId(1L);
        row.setPosCount(2.0);
        row.setNegCount(0.0);
        row.setLastDecayAt(LocalDateTime.now().minusDays(90));
        when(learnedMapper.selectByPoiAndTag("p1", 1L)).thenReturn(row);

        service.applyEvidence("p1", Map.of(1L, new double[]{0.5, 0.0}), LocalDateTime.now());

        ArgumentCaptor<Double> pos = ArgumentCaptor.forClass(Double.class);
        verify(learnedMapper).upsertEvidence(eq("p1"), eq(1L), pos.capture(), eq(0.0));
        assertEquals(1.5, pos.getValue(), 1e-6);
    }

    @Test
    @DisplayName("不同用户数刷新只 update distinct_users")
    void refreshesDistinctUsers() {
        when(learnedAccountMapper.batchTouch(any(), any(), any(), any())).thenReturn(1);
        when(learnedAccountMapper.countDistinctUsers(any(), any(), any()))
                .thenReturn(List.of(new TagSupportCount(1L, 7)));

        service.touchUsers("p1", List.of(1L, 2L), 9L, LocalDateTime.now());

        verify(learnedMapper).updateDistinctUsers("p1", 1L, 7);
        verify(learnedMapper).updateDistinctUsers("p1", 2L, 0);
    }
}
