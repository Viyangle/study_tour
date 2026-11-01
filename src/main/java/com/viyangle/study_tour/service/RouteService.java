package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Route;
import com.viyangle.study_tour.pojo.RouteAttraction;

import java.util.List;
import java.util.Map;

public interface RouteService {
    Long generateRouteByManual(List<RouteAttraction> routeAttractions);

    Long saveOptimizedRoute(String tag, List<RouteAttraction> routeAttractions);

    Long saveOrUpdateAIConversationRoute(String memoryId, String tag, List<RouteAttraction> routeAttractions);

    /**
     * 保存 AI 路线，可把模型输出的多标签权重融合进路线向量。
     *
     * @param tagVector 模型 tagVector，key 为标签名，value 0~1；可为 null
     */
    Long saveOrUpdateAIConversationRoute(String memoryId,
                                         String tag,
                                         List<RouteAttraction> routeAttractions,
                                         Map<String, Double> tagVector);

    List<Route> getPagedRoutesByPreference(Long accountId, Integer pageNum, Integer pageSize);

    List<RouteAttraction> getRouteById(Long id);

    void addFavorite(Long routeId, Long accountId);

    void removeFavorite(Long routeId, Long accountId);
}
