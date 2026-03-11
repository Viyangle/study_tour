package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.AccountTagPref;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AccountTagPrefMapper {

    int insert(AccountTagPref accountTagPref);

    int deleteById(@Param("accountId") Long accountId, @Param("tagId") Long tagId);

    int deleteByAccountId(Long accountId);

    AccountTagPref selectById(@Param("accountId") Long accountId, @Param("tagId") Long tagId);

    List<AccountTagPref> selectAll();
}
