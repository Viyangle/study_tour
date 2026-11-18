package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.TagSupportCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AttractionTagLearnedAccountMapper {

    int batchTouch(@Param("poiId") String poiId,
                   @Param("tagIds") List<Long> tagIds,
                   @Param("accountId") Long accountId,
                   @Param("seenAt") LocalDateTime seenAt);

    List<TagSupportCount> countDistinctUsers(@Param("poiId") String poiId,
                                             @Param("tagIds") List<Long> tagIds,
                                             @Param("since") LocalDateTime since);
}
