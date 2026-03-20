package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.mapper.RouteAttractionMapper;
import com.viyangle.study_tour.mapper.RouteMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.Route;
import com.viyangle.study_tour.pojo.RouteAttraction;
import com.viyangle.study_tour.service.RouteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RouteServiceImpl implements RouteService {

    @Autowired
    private RouteMapper routeMapper;

    @Autowired
    private RouteAttractionMapper routeAttractionMapper;

    @Autowired
    private AttractionMapper attractionMapper;
    @Transactional
    @Override
    public Long generateRouteByManual(List<RouteAttraction> routeAttractions) {
        Route route = new Route(null, LocalDateTime.now());
        routeMapper.insert(route);
        for (RouteAttraction routeAttraction : routeAttractions) {
            routeAttraction.setRouteId(route.getId());
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
        return route.getId();
    }

    @Override
    public List<RouteAttraction> getRouteById(Long id) {
        return routeAttractionMapper.selectByRouteId(id);
    }
}
