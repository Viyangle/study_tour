package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.RouteAttraction;

import java.util.List;

public interface AiRoutePlanningService {
    AIRoutePlan planRouteV2(String memoryId, String message) throws Exception;

    /**
     * 在前端提交的完整景点集合内优化路线。优化结果必须保留全部景点，且不能引入其他景点。
     */
    AIRoutePlan optimizeSubmittedRoute(List<RouteAttraction> routeAttractions, String message) throws Exception;
}
