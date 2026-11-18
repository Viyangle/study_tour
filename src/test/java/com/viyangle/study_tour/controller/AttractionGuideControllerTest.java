package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.exception.GlobalExceptionHandler;
import com.viyangle.study_tour.pojo.AttractionGuide;
import com.viyangle.study_tour.service.AttractionGuideService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AttractionGuideControllerTest {

    @Mock
    private AttractionGuideService attractionGuideService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AttractionGuideController controller = new AttractionGuideController(attractionGuideService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getPublishedGuide_returnsContentAndJsonArrays() throws Exception {
        AttractionGuide guide = new AttractionGuide();
        guide.setPoiId("POI-1");
        guide.setName("示例景点");
        guide.setAddress("示例地址");
        guide.setSummary("景点简介");
        guide.setContent("详细讲解");
        guide.setStudyPoints(List.of("知识点一", "知识点二"));
        guide.setQuestions(List.of("思考题一"));
        guide.setVisitTips("参观建议");
        guide.setCoverUrl("https://example.com/cover.jpg");
        guide.setSourceName("景点官网");
        guide.setSourceUrl("https://example.com/source");
        guide.setSourceType("WIKIPEDIA");
        guide.setSourceLicense("CC BY-SA 4.0");
        guide.setUpdatedAt(LocalDateTime.of(2026, 11, 17, 16, 30));
        when(attractionGuideService.getPublishedGuideByPoiId("POI-1")).thenReturn(guide);

        mockMvc.perform(get("/attractions/POI-1/guide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.poiId").value("POI-1"))
                .andExpect(jsonPath("$.data.name").value("示例景点"))
                .andExpect(jsonPath("$.data.summary").value("景点简介"))
                .andExpect(jsonPath("$.data.studyPoints[0]").value("知识点一"))
                .andExpect(jsonPath("$.data.studyPoints[1]").value("知识点二"))
                .andExpect(jsonPath("$.data.questions[0]").value("思考题一"))
                .andExpect(jsonPath("$.data.sourceUrl").value("https://example.com/source"));
    }

    @Test
    void getPublishedGuide_returnsNotFoundWhenContentIsMissingOrDraft() throws Exception {
        when(attractionGuideService.getPublishedGuideByPoiId("POI-404")).thenReturn(null);

        mockMvc.perform(get("/attractions/POI-404/guide"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("景点研学讲解不存在或未发布, poiId=POI-404"));
    }
}
