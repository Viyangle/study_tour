package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.AttractionAdjacency;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AttractionAdjacencyMapper {

    int insert(AttractionAdjacency adjacency);

    int batchInsert(@Param("list") List<AttractionAdjacency> list);

    int deleteByFromPoiId(@Param("fromPoiId") String fromPoiId);

    List<AttractionAdjacency> selectByFromPoiId(@Param("fromPoiId") String fromPoiId);

    List<AttractionAdjacency> selectByToPoiId(@Param("toPoiId") String toPoiId);

    /**
     * 查询某景点的所有相邻景点（双向：作为 from 或 to）。
     */
    List<AttractionAdjacency> selectNeighbors(@Param("poiId") String poiId);

    List<AttractionAdjacency> selectAll();
}
