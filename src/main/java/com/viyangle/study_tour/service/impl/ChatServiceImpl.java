package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.ChatMessageMapper;
import com.viyangle.study_tour.mapper.ChatSessionMapper;
import com.viyangle.study_tour.pojo.ChatMessage;
import com.viyangle.study_tour.pojo.ChatSession;
import com.viyangle.study_tour.pojo.CreateChatSessionRequest;
import com.viyangle.study_tour.pojo.SendChatMessageRequest;
import com.viyangle.study_tour.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatServiceImpl implements ChatService {

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Override
    public ChatSession createOrGetSession(CreateChatSessionRequest request) {
        // 先查是否已有会话（因为表上有唯一约束：project_id + user_account_id + leader_account_id）
        ChatSession existing = chatSessionMapper.selectByProjectAndAccounts(
            request.getProjectId(),
            request.getUserAccountId(),
            request.getLeaderAccountId()
        );
        if (existing != null) {
            return existing;
        }

        // 不存在则创建
        ChatSession session = new ChatSession();
        session.setProjectId(request.getProjectId());
        session.setUserAccountId(request.getUserAccountId());
        session.setLeaderAccountId(request.getLeaderAccountId());
        chatSessionMapper.insert(session);
        return chatSessionMapper.selectById(session.getId());
    }

    @Override
    public List<ChatSession> listSessions(Long accountId, String role) {
        if ("LEADER".equalsIgnoreCase(role)) {
            return chatSessionMapper.selectByLeaderAccountId(accountId);
        }
        // 默认按 USER 处理
        return chatSessionMapper.selectByUserAccountId(accountId);
    }

    @Override
    public Long sendMessage(SendChatMessageRequest request) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(request.getSessionId());
        msg.setSenderAccountId(request.getSenderAccountId());
        msg.setContent(request.getContent());
        msg.setMsgType((request.getMsgType() == null || request.getMsgType().isBlank()) ? "TEXT" : request.getMsgType());
        chatMessageMapper.insert(msg);
        return msg.getId();
    }

    @Override
    public List<ChatMessage> listMessages(Long sessionId) {
        return chatMessageMapper.selectBySessionId(sessionId);
    }
}
