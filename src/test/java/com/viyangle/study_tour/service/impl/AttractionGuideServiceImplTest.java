package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AttractionGuideMapper;
import com.viyangle.study_tour.pojo.AttractionGuide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttractionGuideServiceImplTest {

    @Mock
    private AttractionGuideMapper attractionGuideMapper;

    @InjectMocks
    private AttractionGuideServiceImpl attractionGuideService;

    @Test
    void getPublishedGuide_delegatesToMapper() {
        AttractionGuide guide = new AttractionGuide();
        guide.setPoiId("POI-1");
        when(attractionGuideMapper.selectPublishedByPoiId("POI-1")).thenReturn(guide);

        assertThat(attractionGuideService.getPublishedGuideByPoiId("POI-1")).isSameAs(guide);
        verify(attractionGuideMapper).selectPublishedByPoiId("POI-1");
    }

    @Test
    void getPublishedGuide_rejectsBlankPoiIdWithoutQuerying() {
        assertThat(attractionGuideService.getPublishedGuideByPoiId(" ")).isNull();
        verifyNoInteractions(attractionGuideMapper);
    }
}
