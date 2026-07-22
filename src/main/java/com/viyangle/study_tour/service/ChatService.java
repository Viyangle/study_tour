package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.ChatMessage;
import com.viyangle.study_tour.pojo.ChatGroupMember;
import com.viyangle.study_tour.pojo.ChatSession;
import com.viyangle.study_tour.pojo.SendChatMessageRequest;

import java.util.List;

public interface ChatService {
    /**
     * 项目确认领队后由项目服务自动创建唯一群聊，不对前端开放。
     */
    ChatSession createProjectGroup(Long projectId, Long ownerAccountId, Long leaderAccountId);

    /**
     * 项目结束后删除群聊及其全部消息。
     */
    void deleteProjectGroup(Long projectId);

    /**
     * 查询账号作为项目成员或领队参与的群聊。
     */
    List<ChatSession> listSessions(Long accountId);

    /**
     * 发送消息
     * @return 新消息id
     */
    Long sendMessage(SendChatMessageRequest request, Long currentAccountId);

    /**
     * 拉取会话消息列表
     */
    List<ChatMessage> listMessages(Long sessionId, Long currentAccountId);

    /** 查询群成员及每个账号代表的实际参团人数。 */
    List<ChatGroupMember> listGroupMembers(Long sessionId, Long currentAccountId);
}
