package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.RouteAttraction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RouteAttractionMapper {

    int insert(RouteAttraction routeAttraction);

    int deleteByRouteIdAndVisitOrder(@Param("routeId") Long routeId,
                                     @Param("visitOrder") Integer visitOrder);

    int updateByRouteIdAndVisitOrder(RouteAttraction routeAttraction);

    RouteAttraction selectByRouteIdAndVisitOrder(@Param("routeId") Long routeId,
                                                 @Param("visitOrder") Integer visitOrder);

    List<RouteAttraction> selectAll();

    List<RouteAttraction> selectByRouteId(@Param("routeId") Long routeId);
}