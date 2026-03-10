package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.LeaderApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface LeaderApplicationMapper {

    int insert(LeaderApplication leaderApplication);

    int deleteById(@Param("id") Long id);

    int updateById(LeaderApplication leaderApplication);

    LeaderApplication selectById(@Param("id") Long id);

    List<LeaderApplication> selectAll();
}
