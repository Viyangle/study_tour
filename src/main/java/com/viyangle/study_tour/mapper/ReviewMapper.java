package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.Review;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReviewMapper {

    int insert(Review review);

    int deleteById(@Param("id") Long id);

    int updateById(Review review);

    Review selectById(@Param("id") Long id);

    List<Review> selectAll();
    
    List<Review> selectByProjectId(@Param("projectId") Long projectId);
    
    List<Review> selectByRouteId(@Param("routeId") Long routeId);
    
    List<Review> selectByToAccountId(@Param("toAccountId") Long toAccountId);
    
    List<Review> selectByFromAccountId(@Param("fromAccountId") Long fromAccountId);
    
    List<Review> selectByReviewType(@Param("reviewType") String reviewType);
    
    Double selectAverageScoreByToAccountId(@Param("toAccountId") Long toAccountId);
}