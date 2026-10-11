package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.ProjectAbandonRecordMapper;
import com.viyangle.study_tour.mapper.ProjectMapper;
import com.viyangle.study_tour.mapper.ProjectMemberMapper;
import com.viyangle.study_tour.mapper.RouteMapper;
import com.viyangle.study_tour.mapper.TagMapper;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.ProjectAbandonRecord;
import com.viyangle.study_tour.service.ChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「放弃带队」P0 规则的边界回归：6 小时下限（含恰好满足）、原因 5-200 字、
 * 出发时间缺失按「订单时间异常」拒绝、以及放弃记录查询的权限矩阵。
 */
@ExtendWith(MockitoExtension.class)
class ProjectAbandonRulesTest {

    private static final Long PROJECT_ID = 5L;
    private static final Long LEADER_ID = 20L;
    private static final Long OWNER_ID = 10L;
    private static final String VALID_REASON = "临时家里有事，无法带队";

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @Mock
    private ProjectAbandonRecordMapper projectAbandonRecordMapper;

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

    @InjectMocks
    private ProjectServiceImpl projectService;

    // ---------- 6 小时下限 ----------

    @Test
    void abandonIsRejectedWhenDepartureIsWithinSixHours() {
        Project project = project(LocalDateTime.now().plusHours(5).plusMinutes(59));
        when(projectMapper.selectByIdForUpdate(PROJECT_ID)).thenReturn(project);

        assertThrows(ForbiddenException.class,
                () -> projectService.abandonProject(PROJECT_ID, LEADER_ID, VALID_REASON));

        verify(projectMapper, never()).casAbandonProject(any(), any());
        verifyNoInteractions(projectAbandonRecordMapper, chatService);
    }

    @Test
    void abandonIsAllowedAtTheSixHourBoundary() {
        // 判据是 now.isAfter(departure - 6h)，相等/更晚都允许；这里留 5 秒余量抵消用例自身执行耗时。
        Project project = project(LocalDateTime.now().plusHours(6).plusSeconds(5));
        when(projectMapper.selectByIdForUpdate(PROJECT_ID)).thenReturn(project);
        when(projectMapper.casAbandonProject(PROJECT_ID, LEADER_ID)).thenReturn(1);

        projectService.abandonProject(PROJECT_ID, LEADER_ID, VALID_REASON);

        verify(projectMapper).casAbandonProject(PROJECT_ID, LEADER_ID);
        verify(chatService).removeProjectLeader(PROJECT_ID, LEADER_ID);
    }

    @Test
    void abandonUsesServerTimeAndRejectsDepartedProject() {
        Project project = project(LocalDateTime.now().minusMinutes(1));
        when(projectMapper.selectByIdForUpdate(PROJECT_ID)).thenReturn(project);

        assertThrows(ForbiddenException.class,
                () -> projectService.abandonProject(PROJECT_ID, LEADER_ID, VALID_REASON));

        verify(projectMapper, never()).casAbandonProject(any(), any());
    }

    // ---------- 原因校验 ----------

    @Test
    void abandonReasonMustNotBeMissingOrBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> projectService.abandonProject(PROJECT_ID, LEADER_ID, null));
        assertThrows(IllegalArgumentException.class,
                () -> projectService.abandonProject(PROJECT_ID, LEADER_ID, ""));
        assertThrows(IllegalArgumentException.class,
                () -> projectService.abandonProject(PROJECT_ID, LEADER_ID, "    "));

        verifyNoInteractions(projectMapper, projectAbandonRecordMapper, chatService);
    }

    @Test
    void abandonReasonLengthBoundariesAreRejectedBeforeTouchingTheProject() {
        assertThrows(IllegalArgumentException.class,
                () -> projectService.abandonProject(PROJECT_ID, LEADER_ID, "甲".repeat(4)));
        assertThrows(IllegalArgumentException.class,
                () -> projectService.abandonProject(PROJECT_ID, LEADER_ID, "甲".repeat(201)));

        verifyNoInteractions(projectMapper, projectAbandonRecordMapper, chatService);
    }

    @Test
    void abandonReasonAcceptsFiveToTwoHundredCharsAfterTrim() {
        Project project = project(LocalDateTime.now().plusDays(1));
        when(projectMapper.selectByIdForUpdate(PROJECT_ID)).thenReturn(project);
        when(projectMapper.casAbandonProject(PROJECT_ID, LEADER_ID)).thenReturn(1);

        projectService.abandonProject(PROJECT_ID, LEADER_ID, "  临时家里有事  ");
        projectService.abandonProject(PROJECT_ID, LEADER_ID, "甲".repeat(5));
        projectService.abandonProject(PROJECT_ID, LEADER_ID, "甲".repeat(200));

        ArgumentCaptor<ProjectAbandonRecord> captor = ArgumentCaptor.forClass(ProjectAbandonRecord.class);
        verify(projectAbandonRecordMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        List<ProjectAbandonRecord> records = captor.getAllValues();
        assertEquals("临时家里有事", records.get(0).getReason());
        assertEquals(5, records.get(1).getReason().length());
        assertEquals(200, records.get(2).getReason().length());
    }

    // ---------- 异常时间 ----------

    @Test
    void abandonIsRejectedWhenDepartureDateIsMissing() {
        Project project = project(LocalDateTime.now().plusDays(1));
        project.setDepartureDate(null);
        when(projectMapper.selectByIdForUpdate(PROJECT_ID)).thenReturn(project);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> projectService.abandonProject(PROJECT_ID, LEADER_ID, VALID_REASON));

        assertEquals("订单时间异常，请联系客服处理", ex.getMessage());
        verifyNoInteractions(projectAbandonRecordMapper, chatService);
    }

    @Test
    void abandonIsRejectedWhenDepartureTimeIsMissing() {
        Project project = project(LocalDateTime.now().plusDays(1));
        project.setDepartureTime(null);
        when(projectMapper.selectByIdForUpdate(PROJECT_ID)).thenReturn(project);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> projectService.abandonProject(PROJECT_ID, LEADER_ID, VALID_REASON));

        assertEquals("订单时间异常，请联系客服处理", ex.getMessage());
        verifyNoInteractions(projectAbandonRecordMapper, chatService);
    }

    // ---------- 状态与并发 ----------

    @Test
    void abandonIsRejectedWhenStatusIsNotConfirmed() {
        Project project = project(LocalDateTime.now().plusDays(1));
        project.setStatus("OPEN");
        when(projectMapper.selectByIdForUpdate(PROJECT_ID)).thenReturn(project);

        assertThrows(ForbiddenException.class,
                () -> projectService.abandonProject(PROJECT_ID, LEADER_ID, VALID_REASON));

        verifyNoInteractions(projectAbandonRecordMapper, chatService);
    }

    @Test
    void abandonIsRejectedWhenConcurrentUpdateWinsTheCas() {
        Project project = project(LocalDateTime.now().plusDays(1));
        when(projectMapper.selectByIdForUpdate(PROJECT_ID)).thenReturn(project);
        when(projectMapper.casAbandonProject(PROJECT_ID, LEADER_ID)).thenReturn(0);

        assertThrows(ForbiddenException.class,
                () -> projectService.abandonProject(PROJECT_ID, LEADER_ID, VALID_REASON));

        verifyNoInteractions(projectAbandonRecordMapper, chatService);
    }

    // ---------- 审计记录查询权限 ----------

    @Test
    void abandonRecordsAreVisibleToOwnerCurrentLeaderAndAdmin() {
        Project project = project(LocalDateTime.now().plusDays(1));
        List<ProjectAbandonRecord> records = List.of(record(LEADER_ID));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(projectAbandonRecordMapper.selectByProjectId(PROJECT_ID)).thenReturn(records);

        assertEquals(records, projectService.getAbandonRecords(PROJECT_ID, OWNER_ID, "USER"));
        assertEquals(records, projectService.getAbandonRecords(PROJECT_ID, LEADER_ID, "LEADER"));
        assertEquals(records, projectService.getAbandonRecords(PROJECT_ID, 99L, "ADMIN"));
    }

    @Test
    void abandonRecordsAreVisibleToTheLeaderWhoAlreadyAbandonedTheProject() {
        Project project = project(LocalDateTime.now().plusDays(1));
        project.setLeaderAccountId(null);
        List<ProjectAbandonRecord> records = List.of(record(LEADER_ID));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(projectAbandonRecordMapper.selectByProjectId(PROJECT_ID)).thenReturn(records);

        assertEquals(records, projectService.getAbandonRecords(PROJECT_ID, LEADER_ID, "LEADER"));
    }

    @Test
    void abandonRecordsAreHiddenFromUnrelatedAccounts() {
        Project project = project(LocalDateTime.now().plusDays(1));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(projectAbandonRecordMapper.selectByProjectId(PROJECT_ID)).thenReturn(List.of(record(LEADER_ID)));

        assertThrows(ForbiddenException.class,
                () -> projectService.getAbandonRecords(PROJECT_ID, 77L, "USER"));
    }

    private Project project(LocalDateTime departure) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setOwnerAccountId(OWNER_ID);
        project.setLeaderAccountId(LEADER_ID);
        project.setStatus("CONFIRMED");
        project.setDepartureDate(departure.toLocalDate());
        project.setDepartureTime(departure.toLocalTime());
        return project;
    }

    private ProjectAbandonRecord record(Long leaderAccountId) {
        return new ProjectAbandonRecord(
                null, PROJECT_ID, leaderAccountId, VALID_REASON, "CONFIRMED",
                LocalDateTime.now().plusDays(1), LocalDateTime.now(), null);
    }
}
