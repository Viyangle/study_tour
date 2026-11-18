package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AttractionGuideMapper;
import com.viyangle.study_tour.pojo.AttractionGuide;
import com.viyangle.study_tour.service.AttractionGuideService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AttractionGuideServiceImpl implements AttractionGuideService {

    @Autowired
    private AttractionGuideMapper attractionGuideMapper;

    @Override
    public AttractionGuide getPublishedGuideByPoiId(String poiId) {
        if (poiId == null || poiId.isBlank()) {
            return null;
        }
        return attractionGuideMapper.selectPublishedByPoiId(poiId);
    }
}
