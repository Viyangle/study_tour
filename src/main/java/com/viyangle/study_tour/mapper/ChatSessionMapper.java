package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatSessionMapper {

    int insert(ChatSession chatSession);

    int deleteById(@Param("id") Long id);

    int updateById(ChatSession chatSession);

    ChatSession selectById(@Param("id") Long id);

    List<ChatSession> selectAll();

    /**
     * 按项目+双方账号查会话（用于“存在则返回，不存在则创建”）
     */
    ChatSession selectByProjectAndAccounts(
        @Param("projectId") Long projectId,
        @Param("userAccountId") Long userAccountId,
        @Param("leaderAccountId") Long leaderAccountId
    );

    /**
     * 按用户账号查会话列表
     */
    List<ChatSession> selectByUserAccountId(@Param("userAccountId") Long userAccountId);

    /**
     * 按领队账号查会话列表
     */
    List<ChatSession> selectByLeaderAccountId(@Param("leaderAccountId") Long leaderAccountId);
}
