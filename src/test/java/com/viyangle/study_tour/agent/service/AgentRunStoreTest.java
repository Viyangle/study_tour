package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.config.AgentProperties;
import com.viyangle.study_tour.agent.dto.PlanRequest;
import com.viyangle.study_tour.agent.exception.AgentApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务存储：幂等、会话互斥、限流、账号隔离与过期清理。 */
class AgentRunStoreTest {

    private AgentProperties props;
    private AgentRunStore store;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        props.setMaxActiveRuns(50);
        props.setMaxStoredRuns(500);
        props.setMaxCreatesPerWindow(6);
        props.setCreateWindow(Duration.ofSeconds(60));
        props.setRunTtl(Duration.ofMinutes(30));
        store = new AgentRunStore(props);
    }

    private PlanRequest request(String requestId, String memoryId) {
        PlanRequest request = new PlanRequest();
        request.setRequestId(requestId);
        request.setMemoryId(memoryId);
        request.setAccountId(1L);
        request.setMessage("南京亲子历史研学");
        return request;
    }

    @Test
    void createsRunWithHexIdAndQueuedState() {
        AgentRunStore.CreateOutcome outcome = store.createOrReuse(request("req-1", "mem-1"), 1L, "USER", "fp-1");
        assertTrue(outcome.created());
        assertTrue(outcome.entry().getRunId().matches("^[a-f0-9]{32}$"));
        assertEquals(AgentRunStore.STATUS_QUEUED, outcome.entry().getStatus());
        assertEquals("等待规划", outcome.entry().getStage());
    }

    @Test
    void replaysSameRequestIdWithoutCreatingNewRun() {
        AgentRunStore.CreateOutcome first = store.createOrReuse(request("req-1", "mem-1"), 1L, "USER", "fp-1");
        AgentRunStore.CreateOutcome second = store.createOrReuse(request("req-1", "mem-1"), 1L, "USER", "fp-1");
        assertFalse(second.created());
        assertSame(first.entry(), second.entry());
        assertEquals(1, store.createCountWithinWindow(1L));
    }

    @Test
    void rejectsSameRequestIdWithDifferentBody() {
        store.createOrReuse(request("req-1", "mem-1"), 1L, "USER", "fp-1");
        AgentApiException error = assertThrows(AgentApiException.class,
                () -> store.createOrReuse(request("req-1", "mem-2"), 1L, "USER", "fp-2"));
        assertEquals(409, error.getStatus());
    }

    @Test
    void rejectsConcurrentRunForSameSessionAndReleasesAfterFinish() {
        AgentRunStore.CreateOutcome first = store.createOrReuse(request("req-1", "mem-1"), 1L, "USER", "fp-1");
        AgentApiException error = assertThrows(AgentApiException.class,
                () -> store.createOrReuse(request("req-2", "mem-1"), 1L, "USER", "fp-2"));
        assertEquals(409, error.getStatus());

        store.finish(first.entry(), AgentRunStore.STATUS_COMPLETED, "方案已完成", null, null);
        AgentRunStore.CreateOutcome second = store.createOrReuse(request("req-2", "mem-1"), 1L, "USER", "fp-2");
        assertTrue(second.created());
    }

    @Test
    void limitsCreatesPerAccountWindow() {
        for (int i = 0; i < 6; i++) {
            store.createOrReuse(request("req-" + i, "mem-" + i), 1L, "USER", "fp-" + i);
        }
        AgentApiException error = assertThrows(AgentApiException.class,
                () -> store.createOrReuse(request("req-7", "mem-7"), 1L, "USER", "fp-7"));
        assertEquals(429, error.getStatus());
        assertEquals(6, store.createCountWithinWindow(1L));
    }

    @Test
    void limitsGlobalActiveRuns() {
        props.setMaxActiveRuns(1);
        store.createOrReuse(request("req-1", "mem-1"), 1L, "USER", "fp-1");
        AgentApiException error = assertThrows(AgentApiException.class,
                () -> store.createOrReuse(request("req-2", "mem-2"), 2L, "USER", "fp-2"));
        assertEquals(429, error.getStatus());
    }

    @Test
    void scopesRunsToOwningAccount() {
        AgentRunStore.CreateOutcome outcome = store.createOrReuse(request("req-1", "mem-1"), 1L, "USER", "fp-1");
        assertNotNull(store.findForAccount(outcome.entry().getRunId(), 1L));
        assertNull(store.findForAccount(outcome.entry().getRunId(), 2L));
        assertNull(store.findForAccount("ffffffffffffffffffffffffffffffff", 1L));
    }

    @Test
    void dropsTerminalRunsAfterTtl() {
        props.setRunTtl(Duration.ofMillis(-1));
        AgentRunStore.CreateOutcome outcome = store.createOrReuse(request("req-1", "mem-1"), 1L, "USER", "fp-1");
        store.finish(outcome.entry(), AgentRunStore.STATUS_FAILED, "规划失败", null, "boom");
        store.sweepExpired();
        assertNull(store.findForAccount(outcome.entry().getRunId(), 1L));
    }

    @Test
    void cancellingQueuedRunFinishesImmediately() {
        AgentRunStore.CreateOutcome outcome = store.createOrReuse(request("req-1", "mem-1"), 1L, "USER", "fp-1");
        store.requestCancel(outcome.entry());
        assertEquals(AgentRunStore.STATUS_CANCELLED, outcome.entry().getStatus());
        assertEquals("已取消", outcome.entry().getStage());
        assertFalse(outcome.entry().isActive());
    }

    @Test
    void keepsOnlyLatestEvents() {
        AgentRunStore.CreateOutcome outcome = store.createOrReuse(request("req-1", "mem-1"), 1L, "USER", "fp-1");
        for (int i = 0; i < 5; i++) {
            outcome.entry().addEvent(new com.viyangle.study_tour.agent.dto.ToolEvent("search_places",
                    "事件" + i, "completed", i), 3);
        }
        assertEquals(3, outcome.entry().snapshotEvents().size());
        assertEquals("事件4", outcome.entry().snapshotEvents().get(2).getLabel());
    }
}
