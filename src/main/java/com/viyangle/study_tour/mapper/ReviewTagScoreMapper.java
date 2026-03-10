package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.ReviewTagScore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReviewTagScoreMapper {

    int insert(ReviewTagScore reviewTagScore);

    int deleteById(@Param("reviewId") Long reviewId, @Param("tagId") Long tagId);

    int updateById(ReviewTagScore reviewTagScore);

    ReviewTagScore selectById(@Param("reviewId") Long reviewId, @Param("tagId") Long tagId);

    List<ReviewTagScore> selectAll();
}
