package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.mapper.ChatMessageMapper;
import com.viyangle.study_tour.mapper.ChatSessionMapper;
import com.viyangle.study_tour.mapper.ProjectMapper;
import com.viyangle.study_tour.pojo.ChatSession;
import com.viyangle.study_tour.pojo.ChatGroupMember;
import com.viyangle.study_tour.pojo.ChatMessage;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.SendChatMessageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private ChatSessionMapper chatSessionMapper;

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private ChatServiceImpl chatService;

    @Test
    void projectGroupIsCreatedOnlyOnce() {
        ChatSession existing = session(8L, "ACTIVE");
        when(chatSessionMapper.selectByProjectId(3L)).thenReturn(existing);

        chatService.createProjectGroup(3L, 10L, 20L);

        verify(chatSessionMapper, never()).insertProjectGroup(3L, 10L, 20L);
    }

    @Test
    void disabledProjectGroupRejectsNewMessages() {
        ChatSession disabled = session(8L, "DISABLED");
        when(chatSessionMapper.selectById(8L)).thenReturn(disabled);
        when(chatSessionMapper.countParticipant(8L, 10L)).thenReturn(1);
        SendChatMessageRequest request = new SendChatMessageRequest();
        request.setSessionId(8L);
        request.setContent("测试消息");

        assertThrows(ForbiddenException.class, () -> chatService.sendMessage(request, 10L));

        verify(chatMessageMapper, never()).insertIfSessionActive(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void activeProjectMemberCanSendMessage() {
        when(chatSessionMapper.selectById(8L)).thenReturn(session(8L, "ACTIVE"));
        when(chatSessionMapper.countParticipant(8L, 10L)).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<com.viyangle.study_tour.pojo.ChatMessage>getArgument(0).setId(30L);
            return 1;
        }).when(chatMessageMapper).insertIfSessionActive(any());
        SendChatMessageRequest request = new SendChatMessageRequest();
        request.setSessionId(8L);
        request.setContent("测试消息");

        Long messageId = chatService.sendMessage(request, 10L);

        assertEquals(30L, messageId);
    }

    @Test
    void participantCanLeaveGroup() {
        when(chatSessionMapper.selectById(8L)).thenReturn(session(8L, "ACTIVE"));
        when(chatSessionMapper.selectGroupMemberRole(8L, 10L)).thenReturn("PARTICIPANT");

        chatService.leaveGroup(8L, 10L);

        verify(chatSessionMapper).leaveGroupParticipant(8L, 10L);
    }

    @Test
    void expiredOpenProjectRejectsGroupJoin() {
        when(chatSessionMapper.selectById(8L)).thenReturn(session(8L, "ACTIVE"));
        Project project = new Project();
        project.setId(3L);
        project.setStatus("OPEN");
        project.setDepartureDate(LocalDate.now().minusDays(1));
        when(projectMapper.selectByIdForUpdate(3L)).thenReturn(project);

        assertThrows(ForbiddenException.class, () -> chatService.joinGroup(8L, 10L));

        verify(chatSessionMapper, never()).selectEligibleMemberRole(8L, 10L);
    }

    @Test
    void joiningProjectReactivatesItsDisabledGroup() {
        ChatSession disabled = session(8L, "DISABLED");
        ChatSession active = session(8L, "ACTIVE");
        when(chatSessionMapper.selectByProjectId(3L)).thenReturn(disabled, active);
        when(chatSessionMapper.selectEligibleMemberRole(8L, 10L)).thenReturn("PARTICIPANT");

        chatService.joinProjectGroup(3L, 10L);

        verify(chatSessionMapper).reactivateByProjectId(3L);
        verify(chatSessionMapper).upsertGroupMember(8L, 10L, "PARTICIPANT");
    }

    @Test
    void publisherCannotLeaveGroup() {
        when(chatSessionMapper.selectById(8L)).thenReturn(session(8L, "ACTIVE"));
        when(chatSessionMapper.selectGroupMemberRole(8L, 10L)).thenReturn("PUBLISHER");

        assertThrows(ForbiddenException.class, () -> chatService.leaveGroup(8L, 10L));

        verify(chatSessionMapper, never()).leaveGroupParticipant(8L, 10L);
    }

    @Test
    void leaderCannotLeaveGroup() {
        when(chatSessionMapper.selectById(8L)).thenReturn(session(8L, "ACTIVE"));
        when(chatSessionMapper.selectGroupMemberRole(8L, 10L)).thenReturn("LEADER");

        assertThrows(ForbiddenException.class, () -> chatService.leaveGroup(8L, 10L));

        verify(chatSessionMapper, never()).leaveGroupParticipant(8L, 10L);
    }

    @Test
    void nonProjectMemberCannotReadGroupMessages() {
        when(chatSessionMapper.selectById(8L)).thenReturn(session(8L, "ACTIVE"));
        when(chatSessionMapper.countParticipant(8L, 99L)).thenReturn(0);

        assertThrows(ForbiddenException.class, () -> chatService.listMessages(8L, 99L, 1, 30));
    }

    @Test
    void firstMessagePageLoadsLatestThirtyMessages() {
        when(chatSessionMapper.selectById(8L)).thenReturn(session(8L, "ACTIVE"));
        when(chatSessionMapper.countParticipant(8L, 10L)).thenReturn(1);
        ChatMessage message = new ChatMessage();
        when(chatMessageMapper.selectPageBySessionId(8L, 0L, 30)).thenReturn(List.of(message));

        List<ChatMessage> messages = chatService.listMessages(8L, 10L, null, null);

        assertEquals(List.of(message), messages);
        verify(chatMessageMapper).selectPageBySessionId(8L, 0L, 30);
    }

    @Test
    void secondMessagePageLoadsThePreviousThirtyMessages() {
        when(chatSessionMapper.selectById(8L)).thenReturn(session(8L, "ACTIVE"));
        when(chatSessionMapper.countParticipant(8L, 10L)).thenReturn(1);
        when(chatMessageMapper.selectPageBySessionId(8L, 30L, 30)).thenReturn(List.of());

        chatService.listMessages(8L, 10L, 2, 30);

        verify(chatMessageMapper).selectPageBySessionId(8L, 30L, 30);
    }

    @Test
    void deletingProjectGroupSoftDeletesSessionAndKeepsMessages() {
        when(chatSessionMapper.selectByProjectId(3L)).thenReturn(session(8L, "ACTIVE"));

        chatService.deleteProjectGroup(3L);

        verify(chatSessionMapper).deactivateByProjectId(3L);
        verify(chatMessageMapper, never()).deleteBySessionId(8L);
        verify(chatSessionMapper, never()).deleteByProjectId(3L);
    }

    @Test
    void quittingProjectRemovesParticipantFromItsGroup() {
        chatService.leaveProjectGroup(3L, 10L);

        verify(chatSessionMapper).leaveProjectParticipant(3L, 10L);
    }

    @Test
    void abandoningProjectClearsLeaderFromSessionAndGroup() {
        chatService.removeProjectLeader(3L, 20L);

        verify(chatSessionMapper).updateLeaderByProjectId(3L, null);
        verify(chatSessionMapper).removeProjectLeader(3L, 20L);
    }

    @Test
    void groupMemberListIncludesRepresentedPeopleText() {
        when(chatSessionMapper.selectById(8L)).thenReturn(session(8L, "ACTIVE"));
        when(chatSessionMapper.countParticipant(8L, 10L)).thenReturn(1);
        ChatGroupMember member = new ChatGroupMember(10L, "张三", null, "PARTICIPANT", 3);
        when(chatSessionMapper.selectGroupMembers(8L)).thenReturn(List.of(member));

        List<ChatGroupMember> members = chatService.listGroupMembers(8L, 10L);

        assertEquals(3, members.get(0).getRepresentedCount());
        assertEquals("该用户代表3人", members.get(0).getRepresentationText());
    }

    private ChatSession session(Long id, String status) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setProjectId(3L);
        session.setStatus(status);
        return session;
    }
}
