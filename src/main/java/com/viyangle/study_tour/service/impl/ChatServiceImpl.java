package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import com.viyangle.study_tour.mapper.ChatMessageMapper;
import com.viyangle.study_tour.mapper.ChatSessionMapper;
import com.viyangle.study_tour.mapper.ProjectMapper;
import com.viyangle.study_tour.pojo.ChatMessage;
import com.viyangle.study_tour.pojo.ChatGroupMember;
import com.viyangle.study_tour.pojo.ChatSession;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.ProjectStatus;
import com.viyangle.study_tour.pojo.SendChatMessageRequest;
import com.viyangle.study_tour.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
public class ChatServiceImpl implements ChatService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_PARTICIPANT = "PARTICIPANT";
    private static final int DEFAULT_MESSAGE_PAGE_SIZE = 30;
    private static final int MAX_MESSAGE_PAGE_SIZE = 100;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Override
    @Transactional
    public ChatSession createGroup(Long projectId, Long currentAccountId) {
        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        Project project = requireActiveProject(projectId);
        if (!currentAccountId.equals(project.getOwnerAccountId())) {
            throw new ForbiddenException("仅项目发布者可创建或恢复群组");
        }

        ChatSession session = chatSessionMapper.selectByProjectId(projectId);
        if (session == null) {
            session = createProjectGroup(
                    projectId,
                    project.getOwnerAccountId(),
                    project.getLeaderAccountId()
            );
        } else {
            chatSessionMapper.reactivateByProjectId(projectId);
            synchronizeGroupMembers(
                    session,
                    project.getOwnerAccountId(),
                    project.getLeaderAccountId()
            );
        }
        return chatSessionMapper.selectByProjectId(projectId);
    }

    @Override
    @Transactional
    public ChatSession joinGroup(Long sessionId, Long currentAccountId) {
        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ResourceNotFoundException("群组不存在, sessionId=" + sessionId);
        }
        if (!STATUS_ACTIVE.equalsIgnoreCase(session.getStatus())) {
            throw new ForbiddenException("群组已删除或停用");
        }
        requireActiveProject(session.getProjectId());
        String memberRole = chatSessionMapper.selectEligibleMemberRole(sessionId, currentAccountId);
        if (memberRole == null || memberRole.isBlank()) {
            throw new ForbiddenException("仅项目发布者、领队或参团成员可加入群组");
        }
        chatSessionMapper.upsertGroupMember(sessionId, currentAccountId, memberRole);
        ChatSession joined = chatSessionMapper.selectById(sessionId);
        joined.setCurrentUserRole(memberRole);
        return joined;
    }

    @Override
    @Transactional
    public void leaveGroup(Long sessionId, Long currentAccountId) {
        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("群组ID不能为空");
        }
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ResourceNotFoundException("群组不存在, sessionId=" + sessionId);
        }

        String memberRole = chatSessionMapper.selectGroupMemberRole(sessionId, currentAccountId);
        if (memberRole == null || memberRole.isBlank()) {
            throw new ForbiddenException("当前账号不是该群聊成员");
        }
        if (!ROLE_PARTICIPANT.equalsIgnoreCase(memberRole)) {
            throw new ForbiddenException("仅普通成员可退出群聊，发布者或领队不能退出");
        }

        // 仅改变群聊成员状态，保留项目成员关系和历史消息；重复退出按成功处理。
        chatSessionMapper.leaveGroupParticipant(sessionId, currentAccountId);
    }

    @Override
    @Transactional
    public void deleteGroup(Long sessionId, Long currentAccountId, String currentRole) {
        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        Project project = projectMapper.selectById(session.getProjectId());
        boolean isOwner = project != null
                && currentAccountId.equals(project.getOwnerAccountId());
        boolean isAdmin = ROLE_ADMIN.equalsIgnoreCase(currentRole);
        if (!isOwner && !isAdmin) {
            throw new ForbiddenException("仅项目发布者或管理员可删除群组");
        }
        chatSessionMapper.deactivateById(sessionId);
    }

    @Override
    @Transactional
    public ChatSession createProjectGroup(Long projectId, Long ownerAccountId, Long leaderAccountId) {
        if (projectId == null || ownerAccountId == null) {
            throw new IllegalArgumentException("创建项目群聊需要项目和发布者账号ID");
        }

        ChatSession existing = chatSessionMapper.selectByProjectId(projectId);
        if (existing != null) {
            chatSessionMapper.updateLeaderByProjectId(projectId, leaderAccountId);
            chatSessionMapper.reactivateByProjectId(projectId);
            existing = chatSessionMapper.selectByProjectId(projectId);
            synchronizeGroupMembers(existing, ownerAccountId, leaderAccountId);
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
        synchronizeGroupMembers(created, ownerAccountId, leaderAccountId);
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
        // 使用软删除保留审计和历史消息；发送消息通过会话状态原子校验。
        chatSessionMapper.deactivateByProjectId(projectId);
    }

    @Override
    @Transactional
    public void joinProjectGroup(Long projectId, Long accountId) {
        if (projectId == null || accountId == null) {
            return;
        }
        ChatSession session = chatSessionMapper.selectByProjectId(projectId);
        if (session == null) {
            Project project = projectMapper.selectById(projectId);
            if (project == null) {
                throw new ResourceNotFoundException("项目不存在, projectId=" + projectId);
            }
            session = createProjectGroup(
                    projectId,
                    project.getOwnerAccountId(),
                    project.getLeaderAccountId()
            );
        }
        if (!STATUS_ACTIVE.equalsIgnoreCase(session.getStatus())) {
            chatSessionMapper.reactivateByProjectId(projectId);
            session = chatSessionMapper.selectByProjectId(projectId);
        }
        if (STATUS_ACTIVE.equalsIgnoreCase(session.getStatus())) {
            String role = chatSessionMapper.selectEligibleMemberRole(session.getId(), accountId);
            if (role != null && !role.isBlank()) {
                chatSessionMapper.upsertGroupMember(session.getId(), accountId, role);
            }
        }
    }

    @Override
    @Transactional
    public void leaveProjectGroup(Long projectId, Long accountId) {
        if (projectId == null || accountId == null) {
            return;
        }
        chatSessionMapper.leaveProjectParticipant(projectId, accountId);
    }

    @Override
    @Transactional
    public void removeProjectLeader(Long projectId, Long leaderAccountId) {
        if (projectId == null || leaderAccountId == null) {
            return;
        }
        chatSessionMapper.updateLeaderByProjectId(projectId, null);
        chatSessionMapper.removeProjectLeader(projectId, leaderAccountId);
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
    public List<ChatMessage> listMessages(Long sessionId,
                                          Long currentAccountId,
                                          Integer pageNum,
                                          Integer pageSize) {
        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }

        requireSessionParticipant(sessionId, currentAccountId);
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1
                ? DEFAULT_MESSAGE_PAGE_SIZE
                : Math.min(pageSize, MAX_MESSAGE_PAGE_SIZE);
        long offset = (long) (page - 1) * size;
        return chatMessageMapper.selectPageBySessionId(sessionId, offset, size);
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

    private Project requireActiveProject(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("项目ID不能为空");
        }
        Project project = projectMapper.selectByIdForUpdate(projectId);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在, projectId=" + projectId);
        }
        ProjectStatus status = ProjectStatus.from(project.getStatus());
        if (status == ProjectStatus.DONE || status == ProjectStatus.CANCELLED) {
            throw new ForbiddenException("已完成或已取消的项目不能创建群组");
        }
        if ((status == ProjectStatus.OPEN || status == ProjectStatus.MATCHING)
                && hasDeparted(project)) {
            throw new ForbiddenException("project has departed, projectId=" + projectId);
        }
        return project;
    }

    private boolean hasDeparted(Project project) {
        if (project.getDepartureDate() == null) {
            return false;
        }
        LocalTime departureTime = project.getDepartureTime() == null
                ? LocalTime.MAX
                : project.getDepartureTime();
        return LocalDateTime.of(project.getDepartureDate(), departureTime)
                .isBefore(LocalDateTime.now());
    }

    private void synchronizeGroupMembers(ChatSession session,
                                         Long ownerAccountId,
                                         Long leaderAccountId) {
        chatSessionMapper.backfillProjectMembers(session.getId(), session.getProjectId());
        chatSessionMapper.upsertGroupMember(
                session.getId(),
                ownerAccountId,
                "PUBLISHER"
        );
        if (leaderAccountId != null && !leaderAccountId.equals(ownerAccountId)) {
            chatSessionMapper.upsertGroupMember(
                    session.getId(),
                    leaderAccountId,
                    "LEADER"
            );
        }
    }
}
