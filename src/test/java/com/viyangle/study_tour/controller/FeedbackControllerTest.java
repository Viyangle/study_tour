package com.viyangle.study_tour.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.pojo.TagDimension;
import com.viyangle.study_tour.pojo.TagFeedbackEvent;
import com.viyangle.study_tour.service.TagFeedbackService;
import com.viyangle.study_tour.service.TagVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FeedbackControllerTest {

    @Mock
    private TagFeedbackService tagFeedbackService;

    @Mock
    private TagVectorService tagVectorService;

    private FeedbackController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new FeedbackController();
        ReflectionTestUtils.setField(controller, "tagFeedbackService", tagFeedbackService);
        ReflectionTestUtils.setField(controller, "tagVectorService", tagVectorService);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        TagDimension dimension = new TagDimension();
        dimension.setDimVersion(1);
        dimension.setOrdinal(1);
        dimension.setTagId(1L);
        dimension.setCode("HISTORY_HUMANITIES");
        dimension.setName("历史人文");
        dimension.setEnabled(true);
        when(tagVectorService.dimensions()).thenReturn(List.of(dimension));
        when(tagFeedbackService.record(any())).thenReturn(1);
    }

    @Test
    @DisplayName("批量事件返回 accepted/duplicates/ignored")
    void ingestReturnsCounts() throws Exception {
        mockMvc.perform(post("/feedback/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"events":[
                                  {"type":"THUMBS_UP","poiId":"p1","idempotencyKey":"e1"},
                                  {"type":"NOT_A_TYPE","poiId":"p1","idempotencyKey":"e2"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.ignored").value(1));
    }

    @Test
    @DisplayName("TAG_CORRECTION 解析 tagCode 并写入 tagId")
    void tagCorrectionResolvesTagId() throws Exception {
        mockMvc.perform(post("/feedback/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"events":[
                                  {"type":"TAG_CORRECTION","poiId":"p1","tagCode":"HISTORY_HUMANITIES",
                                   "value":-1,"idempotencyKey":"e3"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(1));

        ArgumentCaptor<TagFeedbackEvent> captor = ArgumentCaptor.forClass(TagFeedbackEvent.class);
        verify(tagFeedbackService).record(captor.capture());
        assertEquals(1L, captor.getValue().getTagId());
        assertEquals(-2.0, captor.getValue().getEventValue(), 1e-9);
    }
}
