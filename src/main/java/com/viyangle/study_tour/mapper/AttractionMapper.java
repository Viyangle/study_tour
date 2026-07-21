package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.Attraction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AttractionMapper {

    int insert(Attraction attraction);

    int deleteByPoiId(@Param("poiId") String poiId);

    int updateByPoiId(Attraction attraction);

    Attraction selectByPoiId(@Param("poiId") String poiId);

    List<Attraction> selectByPoiIds(@Param("poiIds") List<String> poiIds);

    List<Attraction> selectActiveByPoiIds(@Param("poiIds") List<String> poiIds);

    List<Attraction> selectAll();

    List<Attraction> selectAllActive();

    List<Attraction> selectByRegionCode(@Param("regionCode") String regionCode);
}
