package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import com.viyangle.study_tour.mapper.ChatMessageMapper;
import com.viyangle.study_tour.mapper.ChatSessionMapper;
import com.viyangle.study_tour.pojo.ChatMessage;
import com.viyangle.study_tour.pojo.ChatGroupMember;
import com.viyangle.study_tour.pojo.ChatSession;
import com.viyangle.study_tour.pojo.SendChatMessageRequest;
import com.viyangle.study_tour.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChatServiceImpl implements ChatService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Override
    public ChatSession createProjectGroup(Long projectId, Long ownerAccountId, Long leaderAccountId) {
        if (projectId == null || ownerAccountId == null || leaderAccountId == null) {
            throw new IllegalArgumentException("创建项目群聊需要项目、发起人和领队账号ID");
        }

        ChatSession existing = chatSessionMapper.selectByProjectId(projectId);
        if (existing != null) {
            return existing;
        }

        try {
            chatSessionMapper.insertProjectGroup(projectId, ownerAccountId, leaderAccountId);
        } catch (DuplicateKeyException ignored) {
            // 并发确认领队时由项目唯一索引保证只创建一个群聊。
        }
        ChatSession created = chatSessionMapper.selectByProjectId(projectId);
        if (created == null) {
            throw new IllegalStateException("项目群聊创建失败, projectId=" + projectId);
        }
        return created;
    }

    @Override
    @Transactional
    public void deleteProjectGroup(Long projectId) {
        if (projectId == null) {
            return;
        }
        ChatSession session = chatSessionMapper.selectByProjectId(projectId);
        if (session == null) {
            return;
        }
        // 先锁定并停用会话，防止完成订单与发送消息并发时插入漏删消息。
        chatSessionMapper.deactivateByProjectId(projectId);
        chatMessageMapper.deleteBySessionId(session.getId());
        chatSessionMapper.deleteByProjectId(projectId);
    }

    @Override
    public List<ChatSession> listSessions(Long accountId) {
        if (accountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        return chatSessionMapper.selectByParticipantAccountId(accountId);
    }

    @Override
    public Long sendMessage(SendChatMessageRequest request, Long currentAccountId) {
        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        if (request == null || request.getSessionId() == null) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }

        ChatSession session = requireSessionParticipant(request.getSessionId(), currentAccountId);
        if (!STATUS_ACTIVE.equalsIgnoreCase(session.getStatus())) {
            throw new ForbiddenException("项目已结束，群聊已禁用");
        }

        ChatMessage msg = new ChatMessage();
        msg.setSessionId(request.getSessionId());
        msg.setSenderAccountId(currentAccountId);
        msg.setContent(request.getContent());
        String msgType = (request.getMsgType() == null || request.getMsgType().isBlank())
                ? "TEXT"
                : request.getMsgType().trim().toUpperCase();
        if (!"TEXT".equals(msgType)) {
            throw new IllegalArgumentException("目前仅支持TEXT消息");
        }
        msg.setMsgType(msgType);
        if (chatMessageMapper.insertIfSessionActive(msg) <= 0) {
            throw new ForbiddenException("项目已结束，群聊已禁用");
        }
        return msg.getId();
    }

    @Override
    public List<ChatMessage> listMessages(Long sessionId, Long currentAccountId) {
        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }

        requireSessionParticipant(sessionId, currentAccountId);
        return chatMessageMapper.selectBySessionId(sessionId);
    }

    @Override
    public List<ChatGroupMember> listGroupMembers(Long sessionId, Long currentAccountId) {
        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        requireSessionParticipant(sessionId, currentAccountId);
        return chatSessionMapper.selectGroupMembers(sessionId);
    }

    private ChatSession requireSessionParticipant(Long sessionId, Long currentAccountId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ResourceNotFoundException("会话不存在, sessionId=" + sessionId);
        }

        if (chatSessionMapper.countParticipant(sessionId, currentAccountId) <= 0) {
            throw new ForbiddenException("无权访问该会话");
        }
        return session;
    }
}
