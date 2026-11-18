package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.TagImpression;
import com.viyangle.study_tour.pojo.TagPopularity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TagImpressionMapper {

    int batchInsert(@Param("list") List<TagImpression> impressions);

    List<TagPopularity> selectPopularitySince(@Param("since") LocalDateTime since);

    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);
}
