package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.Attraction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AttractionMapper {

    int insert(Attraction attraction);

    int deleteById(@Param("id") Long id);

    int updateById(Attraction attraction);

    Attraction selectById(@Param("id") Long id);

    List<Attraction> selectAll();
}
