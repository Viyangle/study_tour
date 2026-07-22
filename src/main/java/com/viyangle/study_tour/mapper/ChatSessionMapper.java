package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.ChatSession;
import com.viyangle.study_tour.pojo.ChatGroupMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatSessionMapper {

    int insertProjectGroup(@Param("projectId") Long projectId,
                           @Param("ownerAccountId") Long ownerAccountId,
                           @Param("leaderAccountId") Long leaderAccountId);

    ChatSession selectById(@Param("id") Long id);

    ChatSession selectByProjectId(@Param("projectId") Long projectId);

    int deactivateByProjectId(@Param("projectId") Long projectId);

    int deleteByProjectId(@Param("projectId") Long projectId);

    List<ChatSession> selectByParticipantAccountId(@Param("accountId") Long accountId);

    int countParticipant(@Param("sessionId") Long sessionId, @Param("accountId") Long accountId);

    List<ChatGroupMember> selectGroupMembers(@Param("sessionId") Long sessionId);
}
