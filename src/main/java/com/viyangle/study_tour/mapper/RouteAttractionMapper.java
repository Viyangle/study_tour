package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.RouteAttraction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RouteAttractionMapper {

    int insert(RouteAttraction routeAttraction);

    int deleteById(@Param("id") Long id);

    int updateById(RouteAttraction routeAttraction);

    RouteAttraction selectById(@Param("id") Long id);

    List<RouteAttraction> selectAll();
}
