package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.AttractionGuide;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AttractionGuideMapper {

    AttractionGuide selectPublishedByPoiId(@Param("poiId") String poiId);

    AttractionGuide selectByPoiId(@Param("poiId") String poiId);

    int upsert(AttractionGuide guide);

    List<String> selectPoiIdsNeedingSync(@Param("limit") int limit,
                                         @Param("refreshDays") int refreshDays);

    List<String> selectActivePoiIds(@Param("limit") int limit);
}
