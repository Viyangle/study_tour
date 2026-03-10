package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TagMapper {

    int insert(Tag tag);

    int deleteById(@Param("id") Long id);

    int updateById(Tag tag);

    Tag selectById(@Param("id") Long id);

    List<Tag> selectAll();
}
