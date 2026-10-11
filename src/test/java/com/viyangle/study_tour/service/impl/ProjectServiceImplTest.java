package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.ProjectMapper;
import com.viyangle.study_tour.mapper.ProjectMemberMapper;
import com.viyangle.study_tour.mapper.RouteMapper;
import com.viyangle.study_tour.mapper.TagMapper;
import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.ProjectMember;
import com.viyangle.study_tour.service.ChatService;
import com.viyangle.study_tour.service.KnowledgeGraphRecommendService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @Mock
    private com.viyangle.study_tour.mapper.ProjectAbandonRecordMapper projectAbandonRecordMapper;

    @Mock
    private RouteMapper routeMapper;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private AccountTagPrefMapper accountTagPrefMapper;

    @Mock
    private TagMapper tagMapper;

    @Mock
    private ChatService chatService;

    @Mock
    private KnowledgeGraphRecommendService knowledgeGraphRecommendService;

    @InjectMocks
    private ProjectServiceImpl projectService;

    @Test
    void keywordSearchUsesExistingCompositeFilter() {
        projectService.getPagedProjectsByPreference(6L, 2, 5, " 南京 ");

        verify(projectMapper).selectByCompositeFilter(
                eq(java.util.List.of()),
                isNull(),
                eq("南京"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        );
    }

    @Test
    void acceptingProjectCreatesItsGroupChat() {
        Project project = project(5L, "OPEN");
        project.setLeaderAccountId(null);
        when(projectMapper.selectByIdForUpdate(5L)).thenReturn(project);
        Account leader = new Account();
        leader.setId(20L);
        leader.setRole("LEADER");
        when(accountMapper.selectById(20L)).thenReturn(leader);
        when(projectMapper.casAcceptProject(5L, 20L)).thenReturn(1);

        projectService.acceptProject(5L, 20L);

        verify(projectMapper).casAcceptProject(5L, 20L);
        verify(chatService).createProjectGroup(5L, 10L, 20L);
    }

    @Test
    void bothRoleAccountCanAcceptProject() {
        Project project = project(5L, "OPEN");
        project.setLeaderAccountId(null);
        when(projectMapper.selectByIdForUpdate(5L)).thenReturn(project);
        Account leader = new Account();
        leader.setId(20L);
        leader.setRole("BOTH");
        when(accountMapper.selectById(20L)).thenReturn(leader);
        when(projectMapper.casAcceptProject(5L, 20L)).thenReturn(1);

        projectService.acceptProject(5L, 20L);

        verify(projectMapper).casAcceptProject(5L, 20L);
        verify(chatService).createProjectGroup(5L, 10L, 20L);
    }

    @Test
    void acceptingDepartedProjectIsRejected() {
        Project project = project(5L, "OPEN");
        project.setLeaderAccountId(null);
        project.setDepartureDate(LocalDate.now().minusDays(1));
        when(projectMapper.selectByIdForUpdate(5L)).thenReturn(project);
        Account leader = new Account();
        leader.setId(20L);
        leader.setRole("LEADER");
        when(accountMapper.selectById(20L)).thenReturn(leader);

        assertThrows(ForbiddenException.class, () -> projectService.acceptProject(5L, 20L));
    }

    @Test
    void completingProjectDeletesItsGroupChat() {
        Project project = project(5L, "IN_PROGRESS");
        when(projectMapper.selectById(5L)).thenReturn(project);
        when(projectMapper.casTransitionStatus(5L, "IN_PROGRESS", "DONE")).thenReturn(1);

        projectService.transitionProjectStatus(5L, "DONE", 10L, "USER");

        verify(projectMapper).casTransitionStatus(5L, "IN_PROGRESS", "DONE");
        verify(chatService).deleteProjectGroup(5L);
    }

    @Test
    void joiningProjectAddsRepresentedPeopleInsteadOfOneAccount() {
        Project project = project(5L, "OPEN");
        project.setMaxMembers(10);
        when(projectMapper.selectByIdForUpdate(5L)).thenReturn(project);
        when(projectMapper.casIncrementCurrentMembers(5L, 4)).thenReturn(1);

        projectService.joinProject(5L, 30L, 4);

        ArgumentCaptor<ProjectMember> memberCaptor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberMapper).insert(memberCaptor.capture());
        verify(projectMapper).casIncrementCurrentMembers(5L, 4);
        verify(chatService).joinProjectGroup(5L, 30L);
        assertEquals(4, memberCaptor.getValue().getRepresentedCount());
    }

    @Test
    void joiningProjectRejectsRepresentedPeopleBeyondCapacity() {
        Project project = project(5L, "OPEN");
        project.setMaxMembers(5);
        when(projectMapper.selectByIdForUpdate(5L)).thenReturn(project);
        when(projectMapper.casIncrementCurrentMembers(5L, 2)).thenReturn(0);

        assertThrows(ForbiddenException.class, () -> projectService.joinProject(5L, 30L, 2));
    }

    @Test
    void participantCanQuitProjectAndReleaseRepresentedCapacity() {
        Project project = project(5L, "CONFIRMED");
        ProjectMember membership = new ProjectMember(
                8L, 5L, 30L, "JOINED", 4, null
        );
        when(projectMapper.selectByIdForUpdate(5L)).thenReturn(project);
        when(projectMemberMapper.selectByProjectIdAndAccountId(5L, 30L)).thenReturn(membership);
        when(projectMemberMapper.quitProject(5L, 30L)).thenReturn(1);

        projectService.quitProject(5L, 30L);

        verify(projectMemberMapper).quitProject(5L, 30L);
        verify(projectMapper).refreshCurrentMembersById(5L);
        verify(chatService).leaveProjectGroup(5L, 30L);
    }

    @Test
    void publisherCannotQuitOwnProject() {
        Project project = project(5L, "OPEN");
        when(projectMapper.selectByIdForUpdate(5L)).thenReturn(project);

        assertThrows(ForbiddenException.class, () -> projectService.quitProject(5L, 10L));
    }

    @Test
    void currentLeaderCanAbandonConfirmedProjectAndReopenIt() {
        Project project = project(5L, "CONFIRMED");
        when(projectMapper.selectByIdForUpdate(5L)).thenReturn(project);
        when(projectMapper.casAbandonProject(5L, 20L)).thenReturn(1);

        projectService.abandonProject(5L, 20L, "临时家里有事，无法带队");

        verify(projectMapper).casAbandonProject(5L, 20L);
        verify(projectAbandonRecordMapper).insert(org.mockito.ArgumentMatchers.argThat(record ->
                record.getProjectId().equals(5L)
                        && record.getLeaderAccountId().equals(20L)
                        && record.getReason().equals("临时家里有事，无法带队")
                        && record.getPreviousStatus().equals("CONFIRMED")
                        && record.getAbandonedAt() != null
                        && record.getDepartureAt() != null));
        verify(chatService).removeProjectLeader(5L, 20L);
    }

    @Test
    void nonCurrentLeaderCannotAbandonProject() {
        Project project = project(5L, "CONFIRMED");
        when(projectMapper.selectByIdForUpdate(5L)).thenReturn(project);

        assertThrows(ForbiddenException.class,
                () -> projectService.abandonProject(5L, 99L, "临时家里有事，无法带队"));
    }

    @Test
    void projectWithLeaderCannotTransitionBackToMatching() {
        Project project = project(5L, "OPEN");
        when(projectMapper.selectById(5L)).thenReturn(project);

        assertThrows(IllegalArgumentException.class,
                () -> projectService.transitionProjectStatus(5L, "MATCHING", 10L, "USER"));

        verify(projectMapper, never()).casTransitionStatus(5L, "OPEN", "MATCHING");
    }

    private Project project(Long id, String status) {
        Project project = new Project();
        project.setId(id);
        project.setOwnerAccountId(10L);
        project.setLeaderAccountId(20L);
        project.setStatus(status);
        // 放弃带队要求距出发时间至少 6 小时，这里固定给一个足够远的出发时间。
        project.setDepartureDate(LocalDate.now().plusMonths(1));
        project.setDepartureTime(LocalTime.of(9, 0));
        return project;
    }
}
