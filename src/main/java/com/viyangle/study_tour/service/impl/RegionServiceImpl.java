package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.RegionMapper;
import com.viyangle.study_tour.pojo.Region;
import com.viyangle.study_tour.service.RegionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RegionServiceImpl implements RegionService {

    @Autowired
    private RegionMapper regionMapper;

    @Override
    public List<Region> getProvinces() {
        return regionMapper.selectProvinces();
    }

    @Override
    public List<Region> getChildren(String parentAdcode) {
        return regionMapper.selectChildrenByParentAdcode(parentAdcode);
    }
}
