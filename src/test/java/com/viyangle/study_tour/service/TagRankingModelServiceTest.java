package com.viyangle.study_tour.service;

import com.viyangle.study_tour.mapper.TagRankingModelMapper;
import com.viyangle.study_tour.pojo.TagRankingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagRankingModelServiceTest {

    @Mock
    private TagRankingModelMapper rankingModelMapper;

    @InjectMocks
    private TagRankingModelService service;

    @Test
    @DisplayName("加载 active 模型的 JSON 系数")
    void loadsActiveModelCoefficients() {
        TagRankingModel model = new TagRankingModel();
        model.setModelVersion("v1");
        model.setCoefficients("{\"prior_match\":0.4,\"popularity\":0.1}");
        model.setIntercept(0.05);
        when(rankingModelMapper.selectActive()).thenReturn(model);

        service.refresh();

        assertEquals("v1", service.modelVersion());
        assertEquals(0.05, service.intercept(), 1e-9);
        assertEquals(0.4, service.coefficients().get("prior_match"), 1e-9);
    }
}
