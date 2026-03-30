package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.AIRoutePlan;

public interface AiRoutePlanningService {
    AIRoutePlan planRouteV2(String memoryId, String message) throws Exception;
}
