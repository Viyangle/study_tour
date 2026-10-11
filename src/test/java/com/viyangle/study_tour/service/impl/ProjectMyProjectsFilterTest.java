package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.mapper.ProjectMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.Project;
import com.github.pagehelper.PageHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `GET /projects/mine` 的阶段筛选：`stage` 映射到原始状态集合、优先级，
 * 以及非法 `stage` / 非法原始状态从「静默忽略」变为 400（IllegalArgumentException）。
 */
@ExtendWith(MockitoExtension.class)
class ProjectMyProjectsFilterTest {

    private static final Long ACCOUNT_ID = 10L;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private AccountMapper accountMapper;

    @InjectMocks
    private ProjectServiceImpl projectService;

    @AfterEach
    void clearPageHelper() {
        PageHelper.clearPage();
    }

    @Test
    void acceptedStageMapsToConfirmedAndInProgress() {
        prepareEmptyPage();

        projectService.getMyProjects(ACCOUNT_ID, "ALL", null, "ACCEPTED", null, 1, 10);

        verify(projectMapper).selectByAccountRelation(
                ACCOUNT_ID, "ALL", List.of("CONFIRMED", "IN_PROGRESS"));
    }

    @Test
    void completedStageMapsToDone() {
        prepareEmptyPage();

        projectService.getMyProjects(ACCOUNT_ID, "ALL", null, "COMPLETED", null, 1, 10);

        verify(projectMapper).selectByAccountRelation(ACCOUNT_ID, "ALL", List.of("DONE"));
    }

    @Test
    void stageIsCaseInsensitiveAndTakesPrecedenceOverStatusesAndStatus() {
        prepareEmptyPage();

        projectService.getMyProjects(
                ACCOUNT_ID, "ALL", "DONE", "accepted", List.of("DONE", "CANCELLED"), 1, 10);

        verify(projectMapper).selectByAccountRelation(
                ACCOUNT_ID, "ALL", List.of("CONFIRMED", "IN_PROGRESS"));
    }

    @Test
    void statusesListIsHonouredWhenNoStageIsGiven() {
        prepareEmptyPage();

        projectService.getMyProjects(
                ACCOUNT_ID, "ALL", null, null, List.of("CONFIRMED", "CONFIRMED", "IN_PROGRESS"), 1, 10);

        verify(projectMapper).selectByAccountRelation(
                ACCOUNT_ID, "ALL", List.of("CONFIRMED", "IN_PROGRESS"));
    }

    @Test
    void illegalStageIsRejectedInsteadOfBeingIgnored() {
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> projectService.getMyProjects(ACCOUNT_ID, "ALL", null, "PENDING", null, 1, 10));

        assertTrue(ex.getMessage().contains("PENDING"), ex.getMessage());
        verify(projectMapper, never()).selectByAccountRelation(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void illegalStatusesValueIsRejectedInsteadOfBeingIgnored() {
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account());

        assertThrows(IllegalArgumentException.class,
                () -> projectService.getMyProjects(
                        ACCOUNT_ID, "ALL", null, null, List.of("NOT_A_STATUS"), 1, 10));

        verify(projectMapper, never()).selectByAccountRelation(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noFilterMeansNoStatusConstraint() {
        prepareEmptyPage();

        projectService.getMyProjects(ACCOUNT_ID, "ALL", null, null, null, 1, 10);

        verify(projectMapper).selectByAccountRelation(ACCOUNT_ID, "ALL", null);
    }

    @Test
    void resultIsPaged() {
        prepareEmptyPage();

        assertEquals(0, projectService
                .getMyProjects(ACCOUNT_ID, "ALL", null, "COMPLETED", null, 2, 5)
                .getTotal());
        assertEquals(2, projectService
                .getMyProjects(ACCOUNT_ID, "ALL", null, "COMPLETED", null, 2, 5)
                .getPageNum());
    }

    private void prepareEmptyPage() {
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account());
        when(projectMapper.selectByAccountRelation(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.<Project>of());
    }

    private Account account() {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setRole("USER");
        return account;
    }
}
