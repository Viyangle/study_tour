package com.viyangle.study_tour.service;

import com.viyangle.study_tour.mapper.AccountTagAffinityMapper;
import com.viyangle.study_tour.pojo.AccountTagAffinity;
import com.viyangle.study_tour.pojo.TagDimension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountTagAffinityServiceTest {

    @Mock private AccountTagAffinityMapper affinityMapper;
    @Mock private TagVectorService tagVectorService;

    @InjectMocks
    private AccountTagAffinityService service;

    @BeforeEach
    void setUp() {
        when(tagVectorService.isReady()).thenReturn(true);
        when(tagVectorService.dimensions()).thenReturn(dimensions());
        ReflectionTestUtils.setField(service, "alpha", 0.2);
    }

    private static List<TagDimension> dimensions() {
        List<TagDimension> result = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            TagDimension dimension = new TagDimension();
            dimension.setDimVersion(1);
            dimension.setOrdinal(i);
            dimension.setTagId((long) i);
            dimension.setCode("DIM_" + i);
            dimension.setName("维度" + i);
            dimension.setEnabled(true);
            result.add(dimension);
        }
        return result;
    }

    @Test
    @DisplayName("按 alpha 做指数滑动平均，只更新有 intent 的维度")
    void updatesWithExponentialMovingAverage() {
        AccountTagAffinity existing = new AccountTagAffinity(1L, 1L, 0.2, null);
        when(affinityMapper.selectByAccountId(1L)).thenReturn(List.of(existing));

        service.update(1L, new double[]{1.0, 0.0});

        // 0.2*(1-0.2)+1.0*0.2=0.36
        org.mockito.ArgumentCaptor<Double> captor = org.mockito.ArgumentCaptor.forClass(Double.class);
        verify(affinityMapper).upsert(eq(1L), eq(1L), captor.capture());
        assertEquals(0.36, captor.getValue(), 1e-9);
    }

    @Test
    @DisplayName("读取时按当前维度顺序返回，缺失维度为 0")
    void readsAffinityInDimensionOrder() {
        when(affinityMapper.selectByAccountId(1L)).thenReturn(List.of(
                new AccountTagAffinity(1L, 1L, 0.2, null)));

        double[] affinity = service.affinity(1L);

        assertEquals(0.2, affinity[0], 1e-9);
        assertEquals(0.0, affinity[1], 1e-9);
    }
}
