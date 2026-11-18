package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.AttractionTagLearned;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AttractionTagLearnedMapper {

    List<AttractionTagLearned> selectAll();

    AttractionTagLearned selectByPoiAndTag(@Param("poiId") String poiId,
                                           @Param("tagId") Long tagId);

    int upsertEvidence(@Param("poiId") String poiId,
                       @Param("tagId") Long tagId,
                       @Param("posCount") double posCount,
                       @Param("negCount") double negCount);

    int updateDistinctUsers(@Param("poiId") String poiId,
                            @Param("tagId") Long tagId,
                            @Param("distinctUsers") int distinctUsers);
}
