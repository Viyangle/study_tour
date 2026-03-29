package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.ChatMessage;
import com.viyangle.study_tour.pojo.ChatSession;
import com.viyangle.study_tour.pojo.CreateChatSessionRequest;
import com.viyangle.study_tour.pojo.SendChatMessageRequest;

import java.util.List;

public interface ChatService {
    /**
     * 创建会话（如果已存在则返回已有会话）
     */
    ChatSession createOrGetSession(CreateChatSessionRequest request);

    /**
     * 查询某个账号的会话列表
     * @param role USER 或 LEADER
     */
    List<ChatSession> listSessions(Long accountId, String role);

    /**
     * 发送消息
     * @return 新消息id
     */
    Long sendMessage(SendChatMessageRequest request);

    /**
     * 拉取会话消息列表
     */
    List<ChatMessage> listMessages(Long sessionId);
}
