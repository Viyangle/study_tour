package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.AttractionTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AttractionTagMapper {

    int insert(AttractionTag attractionTag);

    int batchInsert(@Param("list") List<AttractionTag> list);

    int deleteByPoiId(@Param("poiId") String poiId);

    int deleteByPoiIdAndTagId(@Param("poiId") String poiId, @Param("tagId") Long tagId);

    List<AttractionTag> selectByPoiId(@Param("poiId") String poiId);

    List<AttractionTag> selectByTagId(@Param("tagId") Long tagId);

    List<AttractionTag> selectAll();

    /**
     * 查询指定标签名称对应的景点 poiId 列表（跨表联查）。
     */
    List<String> selectPoiIdsByTagName(@Param("tagName") String tagName);

    /**
     * 查询指定标签名称列表对应的景点 poiId 列表（任一标签匹配即可）。
     */
    List<String> selectPoiIdsByTagNames(@Param("tagNames") List<String> tagNames);
}
