package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.RouteAttraction;

import java.util.List;

public interface AiRoutePlanningService {
    List<RouteAttraction> planRouteV2(String memoryId, String message) throws Exception;
}

