package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.config.AgentProperties;
import com.viyangle.study_tour.agent.dto.AgentPlan;
import com.viyangle.study_tour.agent.dto.Clarification;
import com.viyangle.study_tour.agent.dto.Constraints;
import com.viyangle.study_tour.agent.dto.PlanRequest;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.service.AiRoutePlanningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 后台执行：状态流转、事件、失败与取消、追问分支。 */
class AgentRunExecutorTest {

    private AgentProperties props;
    private AgentRunStore store;
    private AiRoutePlanningService planningService;
    private AgentPlanAssembler assembler;
    private AgentRunExecutor executor;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        props.setMaxActiveRuns(50);
        props.setMaxStoredRuns(500);
        props.setExecutionTimeout(Duration.ofSeconds(30));
        props.setSweepInterval(Duration.ofSeconds(30));
        store = new AgentRunStore(props);
        planningService = mock(AiRoutePlanningService.class);
        assembler = mock(AgentPlanAssembler.class);
        executor = new AgentRunExecutor(store, planningService, assembler, props);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        SecurityContextHolder.clearContext();
    }

    private PlanRequest request() {
        PlanRequest request = new PlanRequest();
        request.setRequestId("req-1");
        request.setMemoryId("mem-1");
        request.setAccountId(1L);
        request.setMessage("南京亲子历史研学");
        Constraints constraints = new Constraints();
        constraints.setCity("南京");
        constraints.setMustVisit(new ArrayList<>(List.of("南京博物院")));
        request.setConstraints(constraints);
        return request;
    }

    private AIRoutePlan aiPlan() {
        AIRouteItem item = new AIRouteItem();
        item.setPoiId("POI_A");
        item.setVisitOrder(1);
        item.setRecommendedDuration(60);
        AIRoutePlan plan = new AIRoutePlan();
        plan.setTag("博物馆研学");
        plan.setItems(List.of(item));
        return plan;
    }

    private AgentRunStore.RunEntry submit() {
        PlanRequest request = request();
        AgentRunStore.CreateOutcome outcome =
                store.createOrReuse(request, 1L, "USER", "fp-1");
        executor.submit(outcome.entry(), request, 1L, "USER");
        return outcome.entry();
    }

    private void awaitTerminal(AgentRunStore.RunEntry entry) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (entry.isActive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(entry.isActive(), "任务未在预期时间内结束");
    }

    @Test
    void completesWithPlanAndPropagatesAccountAndConstraints() throws Exception {
        AtomicLong account = new AtomicLong();
        AtomicReference<String> plannerInput = new AtomicReference<>();
        when(planningService.planRouteV2(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            account.set(invocation.getArgument(2));
            plannerInput.set(invocation.getArgument(1));
            return aiPlan();
        });
        AgentPlan plan = new AgentPlan();
        when(assembler.assemble(any(), any(), any(), any()))
                .thenReturn(AgentPlanAssembler.Outcome.ofPlan(plan));

        AgentRunStore.RunEntry entry = submit();
        awaitTerminal(entry);

        assertEquals(AgentRunStore.STATUS_COMPLETED, entry.getStatus());
        assertEquals("方案已完成", entry.getStage());
        assertSame(plan, entry.getResult());
        assertNull(entry.getError());
        assertEquals(1L, account.get());
        assertTrue(plannerInput.get().contains("【约束】"));
        assertTrue(plannerInput.get().contains("必须去：南京博物院"));
        assertTrue(plannerInput.get().contains("【本轮需求】南京亲子历史研学"));
        List<String> labels = entry.snapshotEvents().stream().map(event -> event.getLabel()).toList();
        assertTrue(labels.contains("理解需求"));
        assertTrue(labels.contains("检索候选地点"));
        assertNull(entry.snapshotEvents().get(0).getTool());
    }

    @Test
    void failsWhenPlannerThrows() throws Exception {
        when(planningService.planRouteV2(anyString(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("地图鉴权失败"));

        AgentRunStore.RunEntry entry = submit();
        awaitTerminal(entry);

        assertEquals(AgentRunStore.STATUS_FAILED, entry.getStatus());
        assertEquals("规划失败", entry.getStage());
        assertEquals("地图鉴权失败", entry.getError());
        assertNull(entry.getResult());
    }

    @Test
    void asksClarificationWhenCandidatesAreNotEnough() throws Exception {
        when(planningService.planRouteV2(anyString(), anyString(), anyLong()))
                .thenThrow(new IllegalArgumentException(
                        "Candidate POIs are not enough for routing after applying constraints. 当前候选数: 0"));
        Clarification clarification = new Clarification();
        clarification.setMessage("需要先确认出行城市和可用时长，才能给出方案。");
        clarification.setQuestions(new ArrayList<>(List.of("计划在哪个城市研学？")));
        when(assembler.clarificationForMissingContext(any(), anyString())).thenReturn(clarification);

        AgentRunStore.RunEntry entry = submit();
        awaitTerminal(entry);

        assertEquals(AgentRunStore.STATUS_COMPLETED, entry.getStatus());
        assertEquals("等待你补充需求", entry.getStage());
        assertInstanceOf(Clarification.class, entry.getResult());
        assertNull(entry.getError());
    }

    @Test
    void cancelStopsRunningTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(planningService.planRouteV2(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return aiPlan();
        });

        AgentRunStore.RunEntry entry = submit();
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        store.requestCancel(entry);
        awaitTerminal(entry);

        assertEquals(AgentRunStore.STATUS_CANCELLED, entry.getStatus());
        assertEquals("已取消", entry.getStage());
        assertNull(entry.getResult());
    }
}
