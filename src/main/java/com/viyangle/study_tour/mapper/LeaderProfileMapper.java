package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.LeaderProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface LeaderProfileMapper {

    int insert(LeaderProfile leaderProfile);

    int deleteById(@Param("accountId") Long accountId);

    int updateById(LeaderProfile leaderProfile);

    LeaderProfile selectById(@Param("accountId") Long accountId);

    List<LeaderProfile> selectAll();
}
