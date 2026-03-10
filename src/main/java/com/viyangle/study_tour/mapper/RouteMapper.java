package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.Route;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RouteMapper {

    int insert(Route route);

    int deleteById(@Param("id") Long id);

    int updateById(Route route);

    Route selectById(@Param("id") Long id);

    List<Route> selectAll();
}
