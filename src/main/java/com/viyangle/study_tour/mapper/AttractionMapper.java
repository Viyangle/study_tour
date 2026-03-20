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

    List<Attraction> selectAll();
}
