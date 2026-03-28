package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.mapper.RouteAttractionMapper;
import com.viyangle.study_tour.mapper.RouteMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.Route;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.service.RouteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

@Service
public class RouteServiceImpl implements RouteService {
    private static final String AI_ROUTE_MAPPING_KEY_PREFIX = "ai:route:memory:";


    @Autowired
    private RouteMapper routeMapper;

    @Autowired
    private RouteAttractionMapper routeAttractionMapper;

    @Autowired
    private AttractionMapper attractionMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Transactional
    @Override
    public Long generateRouteByManual(List<RouteAttraction> routeAttractions) {
        Route route = new Route();
        route.setCreatedAt(LocalDateTime.now());
        routeMapper.insert(route);
        upsertRouteAttractions(route.getId(), routeAttractions);
        return route.getId();
    }

    @Transactional
    @Override
    public Long saveOrUpdateAIConversationRoute(String memoryId, String tag, List<RouteAttraction> routeAttractions) {
        String mappingKey = AI_ROUTE_MAPPING_KEY_PREFIX + memoryId;
        String routeIdText = redisTemplate.opsForValue().get(mappingKey);
        String regionAdcode = resolveRegionAdcode(routeAttractions);

        Long routeId = null;
        if (routeIdText != null && !routeIdText.isBlank()) {
            try {
                routeId = Long.parseLong(routeIdText);
            } catch (NumberFormatException ignored) {
                routeId = null;
            }
        }

        if (routeId == null || routeMapper.selectById(routeId) == null) {
            Route route = new Route();
            route.setCreatedAt(LocalDateTime.now());
            route.setRegionAdcode(regionAdcode);
            route.setTag(tag);
            routeMapper.insert(route);
            routeId = route.getId();
        } else {
            Route route = new Route();
            route.setId(routeId);
            route.setRegionAdcode(regionAdcode);
            route.setTag(tag);
            routeMapper.updateById(route);
        }

        routeAttractionMapper.deleteByRouteId(routeId);
        upsertRouteAttractions(routeId, routeAttractions);
        redisTemplate.opsForValue().set(mappingKey, String.valueOf(routeId), Duration.ofDays(1));
        return routeId;
    }

    private String resolveRegionAdcode(List<RouteAttraction> routeAttractions) {
        if (routeAttractions == null || routeAttractions.isEmpty()) {
            return null;
        }

        RouteAttraction first = routeAttractions.stream()
                .filter(ra -> ra != null && ra.getPoiId() != null && !ra.getPoiId().isBlank())
                .min(Comparator.comparingInt(ra -> ra.getVisitOrder() == null ? Integer.MAX_VALUE : ra.getVisitOrder()))
                .orElse(null);

        if (first == null) {
            return null;
        }

        Attraction firstAttraction = attractionMapper.selectByPoiId(first.getPoiId());
        return firstAttraction == null ? null : firstAttraction.getAdcode();
    }

    private void upsertRouteAttractions(Long routeId, List<RouteAttraction> routeAttractions) {
        for (RouteAttraction routeAttraction : routeAttractions) {
            routeAttraction.setRouteId(routeId);
            Attraction a = attractionMapper.selectByPoiId(routeAttraction.getPoiId());
            routeAttraction.setParentPoiId(a.getParentPoiId());
            routeAttraction.setName(a.getName());
            routeAttraction.setAddress(a.getAddress());
            routeAttraction.setLocation(a.getLocation());
            routeAttraction.setPcode(a.getPcode());
            routeAttraction.setPname(a.getPname());
            routeAttraction.setCitycode(a.getCitycode());
            routeAttraction.setCityname(a.getCityname());
            routeAttraction.setAdcode(a.getAdcode());
            routeAttraction.setAdname(a.getAdname());
            routeAttraction.setType(a.getType());
            routeAttraction.setTypecode(a.getTypecode());
            routeAttraction.setDistance(a.getDistance());
            routeAttractionMapper.insert(routeAttraction);
        }
    }

    @Override
    public List<RouteAttraction> getRouteById(Long id) {
        return routeAttractionMapper.selectByRouteId(id);
    }
}
