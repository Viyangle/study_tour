package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import com.viyangle.study_tour.mapper.ChatMessageMapper;
import com.viyangle.study_tour.mapper.ChatSessionMapper;
import com.viyangle.study_tour.pojo.ChatMessage;
import com.viyangle.study_tour.pojo.ChatSession;
import com.viyangle.study_tour.pojo.CreateChatSessionRequest;
import com.viyangle.study_tour.pojo.SendChatMessageRequest;
import com.viyangle.study_tour.service.ChatService;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatServiceImpl implements ChatService {

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Override
    public ChatSession createOrGetSession(CreateChatSessionRequest request) {
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }

        boolean isParticipant = currentAccountId.equals(request.getUserAccountId())
            || currentAccountId.equals(request.getLeaderAccountId());
        if (!isParticipant) {
            throw new ForbiddenException("无权创建或访问该会话");
        }

        ChatSession existing = chatSessionMapper.selectByProjectAndAccounts(
            request.getProjectId(),
            request.getUserAccountId(),
            request.getLeaderAccountId()
        );
        if (existing != null) {
            return existing;
        }

        ChatSession session = new ChatSession();
        session.setProjectId(request.getProjectId());
        session.setUserAccountId(request.getUserAccountId());
        session.setLeaderAccountId(request.getLeaderAccountId());
        chatSessionMapper.insert(session);
        return chatSessionMapper.selectById(session.getId());
    }

    @Override
    public List<ChatSession> listSessions(Long accountId, String role) {
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        String currentRole = SecurityContextUtil.currentRole();

        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }

        boolean isAdmin = "ADMIN".equalsIgnoreCase(currentRole);
        if (!isAdmin && !currentAccountId.equals(accountId)) {
            throw new ForbiddenException("无权查看他人会话");
        }

        if ("LEADER".equalsIgnoreCase(role)) {
            return chatSessionMapper.selectByLeaderAccountId(accountId);
        }
        if ("BOTH".equalsIgnoreCase(role)) {
            List<ChatSession> userSessions = chatSessionMapper.selectByUserAccountId(accountId);
            List<ChatSession> leaderSessions = chatSessionMapper.selectByLeaderAccountId(accountId);
            List<ChatSession> merged = new ArrayList<>(userSessions);
            for (ChatSession session : leaderSessions) {
                boolean exists = merged.stream().anyMatch(s -> s.getId().equals(session.getId()));
                if (!exists) {
                    merged.add(session);
                }
            }
            return merged;
        }
        return chatSessionMapper.selectByUserAccountId(accountId);
    }

    @Override
    public Long sendMessage(SendChatMessageRequest request, Long currentAccountId) {
        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }

        requireSessionParticipant(request.getSessionId(), currentAccountId);

        ChatMessage msg = new ChatMessage();
        msg.setSessionId(request.getSessionId());
        msg.setSenderAccountId(currentAccountId);
        msg.setContent(request.getContent());
        msg.setMsgType((request.getMsgType() == null || request.getMsgType().isBlank()) ? "TEXT" : request.getMsgType());
        chatMessageMapper.insert(msg);
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

    private ChatSession requireSessionParticipant(Long sessionId, Long currentAccountId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ResourceNotFoundException("会话不存在, sessionId=" + sessionId);
        }

        boolean isParticipant = currentAccountId.equals(session.getUserAccountId())
            || currentAccountId.equals(session.getLeaderAccountId());
        if (!isParticipant) {
            throw new ForbiddenException("无权访问该会话");
        }
        return session;
    }
}