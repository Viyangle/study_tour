package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.RouteTagWeight;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RouteTagWeightMapper {

    int deleteByRouteId(@Param("routeId") Long routeId);

    int batchInsert(@Param("list") List<RouteTagWeight> list);

    List<RouteTagWeight> selectByRouteId(@Param("routeId") Long routeId);
}
