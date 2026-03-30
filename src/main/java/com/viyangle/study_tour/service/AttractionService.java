package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Attraction;

import java.util.List;

public interface AttractionService {
    List<Attraction> getAllAttractions();

    List<Attraction> getPagedAttractionsByRegion(String regionCode, Integer pageNum, Integer pageSize);
}
