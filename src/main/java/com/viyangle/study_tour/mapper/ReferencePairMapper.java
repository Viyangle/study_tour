package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.ReferencePair;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReferencePairMapper {

    int insert(ReferencePair referencePair);

    int updateById(ReferencePair referencePair);

    int deleteById(@Param("id") Long id);

    ReferencePair selectById(@Param("id") Long id);

    List<ReferencePair> selectAll();

    List<ReferencePair> selectByTag(@Param("tag") String tag);
}
