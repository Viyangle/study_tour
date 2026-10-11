package com.viyangle.study_tour.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RouteFavoriteMapper {

    int insertIfAbsent(@Param("accountId") Long accountId, @Param("routeId") Long routeId);

    int deleteByAccountIdAndRouteId(@Param("accountId") Long accountId,
                                    @Param("routeId") Long routeId);
}
