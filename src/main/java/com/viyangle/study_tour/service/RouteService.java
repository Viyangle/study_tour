package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Route;
import com.viyangle.study_tour.pojo.RouteAttraction;

import java.util.List;

public interface RouteService {
    Long generateRouteByManual(List<RouteAttraction> routeAttractions);

    Long saveOrUpdateAIConversationRoute(String memoryId, String tag, List<RouteAttraction> routeAttractions);

    List<RouteAttraction> getRouteById(Long id);
}
