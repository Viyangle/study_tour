package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.service.AttractionGuideSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AttractionGuideAdminControllerTest {

    @Mock
    private AttractionGuideSyncService attractionGuideSyncService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AttractionGuideAdminController(attractionGuideSyncService)).build();
    }

    @Test
    void backfillPassesValidatedLimitAndReturnsUpdatedCount() throws Exception {
        when(attractionGuideSyncService.backfillMissing(500)).thenReturn(12);

        mockMvc.perform(post("/admin/attraction-guides/backfill").param("limit", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data").value(12));

        verify(attractionGuideSyncService).backfillMissing(500);
    }

    @Test
    void resyncForcesRecalculationForValidatedLimit() throws Exception {
        when(attractionGuideSyncService.resyncAll(50)).thenReturn(3);

        mockMvc.perform(post("/admin/attraction-guides/resync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(3));

        verify(attractionGuideSyncService).resyncAll(50);
    }
}
