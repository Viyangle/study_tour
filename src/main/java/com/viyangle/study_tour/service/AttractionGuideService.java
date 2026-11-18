package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.AttractionGuide;

public interface AttractionGuideService {

    AttractionGuide getPublishedGuideByPoiId(String poiId);
}
