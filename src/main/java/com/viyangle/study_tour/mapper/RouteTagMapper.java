package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.RouteTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RouteTagMapper {

    int insert(RouteTag routeTag);

    int deleteById(@Param("routeId") Long routeId, @Param("tagId") Long tagId);

    RouteTag selectById(@Param("routeId") Long routeId, @Param("tagId") Long tagId);

    List<RouteTag> selectAll();
}
