package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.ReferenceRouteAttraction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReferenceRouteAttractionMapper {

    int insert(ReferenceRouteAttraction referenceRouteAttraction);

    int deleteByReferenceRouteId(@Param("referenceRouteId") Long referenceRouteId);

    int deleteByReferenceRouteIdAndVisitOrder(@Param("referenceRouteId") Long referenceRouteId,
                                              @Param("visitOrder") Integer visitOrder);

    int updateByReferenceRouteIdAndVisitOrder(ReferenceRouteAttraction referenceRouteAttraction);

    ReferenceRouteAttraction selectByReferenceRouteIdAndVisitOrder(@Param("referenceRouteId") Long referenceRouteId,
                                                                   @Param("visitOrder") Integer visitOrder);

    List<ReferenceRouteAttraction> selectAll();

    List<ReferenceRouteAttraction> selectByReferenceRouteId(@Param("referenceRouteId") Long referenceRouteId);
}
