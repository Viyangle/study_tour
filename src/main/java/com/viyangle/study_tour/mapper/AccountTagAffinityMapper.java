package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.AccountTagAffinity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AccountTagAffinityMapper {

    List<AccountTagAffinity> selectByAccountId(@Param("accountId") Long accountId);

    int upsert(@Param("accountId") Long accountId,
               @Param("tagId") Long tagId,
               @Param("affinity") double affinity);
}
