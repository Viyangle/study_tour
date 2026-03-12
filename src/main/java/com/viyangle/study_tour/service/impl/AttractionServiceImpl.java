package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.service.AttractionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AttractionServiceImpl implements AttractionService {

    @Autowired
    private AttractionMapper attractionMapper;
    @Override
    public List<Attraction> getAllAttractions() {
        return attractionMapper.selectAll();
    }
}
