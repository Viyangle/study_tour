package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.Account;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AccountMapper {

    int insert(Account account);

    int deleteById(@Param("id") Long id);

    int updateById(Account account);

    Account selectById(@Param("id") Long id);

    List<Account> selectAll();
}
