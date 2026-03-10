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
}
