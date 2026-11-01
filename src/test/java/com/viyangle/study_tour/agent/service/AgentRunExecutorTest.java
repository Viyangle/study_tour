package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.config.AgentProperties;
import com.viyangle.study_tour.agent.dto.AgentPlan;
import com.viyangle.study_tour.agent.dto.Clarification;
import com.viyangle.study_tour.agent.dto.Constraints;
import com.viyangle.study_tour.agent.dto.PlanRequest;
import com.viyangle.study_tour.agent.dto.AgentReply;
import com.viyangle.study_tour.utils.SecurityContextUtil;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 后台执行：状态流转、事件、失败与取消、追问分支。 */
class AgentRunExecutorTest {

    private AgentProperties props;
    private AgentRunStore store;
    private AgentLoop agentLoop;
    private AgentRunExecutor executor;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        props.setMaxActiveRuns(50);
        props.setMaxStoredRuns(500);
        props.setExecutionTimeout(Duration.ofSeconds(30));
        props.setSweepInterval(Duration.ofSeconds(30));
        store = new AgentRunStore(props);
        agentLoop = mock(AgentLoop.class);
        executor = new AgentRunExecutor(store, agentLoop, props);
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
    void completesWithPlanAndPropagatesAccountAndOriginalRequest() throws Exception {
        AtomicLong account = new AtomicLong();
        AtomicReference<PlanRequest> received = new AtomicReference<>();
        AgentPlan plan = new AgentPlan();
        when(agentLoop.run(any(), anyLong(), any(), any())).thenAnswer(invocation -> {
            account.set(SecurityContextUtil.currentAccountId());
            received.set(invocation.getArgument(0));
            return plan;
        });

        AgentRunStore.RunEntry entry = submit();
        awaitTerminal(entry);

        assertEquals(AgentRunStore.STATUS_COMPLETED, entry.getStatus());
        assertEquals("方案已完成", entry.getStage());
        assertSame(plan, entry.getResult());
        assertNull(entry.getError());
        assertEquals(1L, account.get());
        assertEquals("南京亲子历史研学", received.get().getMessage());
        assertEquals(List.of("南京博物院"), received.get().getConstraints().getMustVisit());
        assertEquals(1, entry.snapshotEvents().size());
        assertNull(entry.snapshotEvents().get(0).getTool());
    }

    @Test
    void completesWithOrdinaryReplyWithoutInventingToolEvents() throws Exception {
        AgentReply reply = new AgentReply("你好");
        when(agentLoop.run(any(), anyLong(), any(), any())).thenReturn(reply);
        AgentRunStore.RunEntry entry = submit();
        awaitTerminal(entry);
        assertEquals("已回复", entry.getStage());
        assertSame(reply, entry.getResult());
        assertEquals(1, entry.snapshotEvents().size());
    }

    @Test
    void failsWhenPlannerThrows() throws Exception {
        when(agentLoop.run(any(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("地图鉴权失败"));

        AgentRunStore.RunEntry entry = submit();
        awaitTerminal(entry);

        assertEquals(AgentRunStore.STATUS_FAILED, entry.getStatus());
        assertEquals("规划失败", entry.getStage());
        assertEquals("地图鉴权失败", entry.getError());
        assertNull(entry.getResult());
    }

    @Test
    void completesWithModelClarification() throws Exception {
        Clarification clarification = new Clarification();
        clarification.setMessage("请补充城市");
        clarification.setQuestions(List.of("计划在哪个城市研学？"));
        when(agentLoop.run(any(), anyLong(), any(), any())).thenReturn(clarification);
        AgentRunStore.RunEntry entry = submit();
        awaitTerminal(entry);
        assertEquals(AgentRunStore.STATUS_COMPLETED, entry.getStatus());
        assertEquals("等待你补充需求", entry.getStage());
        assertSame(clarification, entry.getResult());
    }

    @Test
    void cancelStopsRunningTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(agentLoop.run(any(), anyLong(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return new AgentPlan();
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
