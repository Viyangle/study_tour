package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Region;

import java.util.List;

public interface RegionService {

    List<Region> getProvinces();

    List<Region> getChildren(String parentAdcode);
}
