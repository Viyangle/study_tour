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

    int upsert(Attraction attraction);

    Attraction selectByPoiId(@Param("poiId") String poiId);

    List<Attraction> selectByPoiIds(@Param("poiIds") List<String> poiIds);

    List<Attraction> selectActiveByPoiIds(@Param("poiIds") List<String> poiIds);

    List<Attraction> selectAll();

    List<Attraction> selectAllActive();

    List<Attraction> selectByRegionCode(@Param("regionCode") String regionCode);

    /**
     * 找出还没有 LLM/规则自动权重（LLM_V2/RULE）的有效景点，供启动或后台补标。
     */
    List<String> selectPoiIdsWithoutTagVector(@Param("limit") int limit);
}
