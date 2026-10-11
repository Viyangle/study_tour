package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.config.AgentProperties;
import com.viyangle.study_tour.agent.dto.PlanRequest;
import com.viyangle.study_tour.agent.dto.ToolEvent;
import com.viyangle.study_tour.agent.exception.AgentApiException;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;

/**
 * 任务存储与限流，全部保存在进程内存中（单 worker），与文档第 8 节描述一致。
 *
 * <p>负责：requestId 幂等、同账号同 memoryId 执行互斥、账号新建频率、全局活跃任务数、
 * 保存任务上限、终态任务过期清理。</p>
 */
@Component
public class AgentRunStore {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    private final AgentProperties props;

    private final ConcurrentMap<String, RunEntry> runs = new ConcurrentHashMap<>();
    /** key: accountId + "|" + requestId -> runId */
    private final ConcurrentMap<String, String> requestIndex = new ConcurrentHashMap<>();
    /** key: accountId + "|" + memoryId -> runId（仅活跃任务） */
    private final ConcurrentMap<String, String> sessionIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Deque<Long>> createTimestamps = new ConcurrentHashMap<>();

    public AgentRunStore(AgentProperties props) {
        this.props = props;
    }

    /** 创建（或幂等复用）任务的结果。 */
    public record CreateOutcome(RunEntry entry, boolean created) {
    }

    @Getter
    public static final class RunEntry {
        private final String runId;
        private final long accountId;
        private final String role;
        private final String memoryId;
        private final String requestId;
        private final String fingerprint;
        private final long createdAtMs = System.currentTimeMillis();
        private final List<ToolEvent> events = Collections.synchronizedList(new ArrayList<>());

        private volatile long finishedAtMs;
        private volatile String status = STATUS_QUEUED;
        private volatile String stage = "等待规划";
        private volatile String error;
        private volatile Object result;
        private volatile boolean cancelRequested;
        private volatile Future<?> task;

        RunEntry(String runId, long accountId, String role, String memoryId, String requestId, String fingerprint) {
            this.runId = runId;
            this.accountId = accountId;
            this.role = role;
            this.memoryId = memoryId;
            this.requestId = requestId;
            this.fingerprint = fingerprint;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public void setStage(String stage) {
            this.stage = stage;
        }

        public void setError(String error) {
            this.error = error;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public void setCancelRequested(boolean cancelRequested) {
            this.cancelRequested = cancelRequested;
        }

        public void setTask(Future<?> task) {
            this.task = task;
        }

        public void setFinishedAtMs(long finishedAtMs) {
            this.finishedAtMs = finishedAtMs;
        }

        public boolean isActive() {
            return STATUS_QUEUED.equals(status) || STATUS_RUNNING.equals(status);
        }

        public boolean isTerminal() {
            return !isActive();
        }

        public void addEvent(ToolEvent event, int maxEvents) {
            synchronized (events) {
                while (events.size() >= maxEvents) {
                    events.remove(0);
                }
                events.add(event);
            }
        }

        public List<ToolEvent> snapshotEvents() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }
    }

    /**
     * 创建任务；同 requestId 同内容时返回已有任务（幂等重试不占用新建额度）。
     *
     * @throws AgentApiException 409 幂等冲突或会话已有活跃任务；429 超出频率/并发/存储上限
     */
    public synchronized CreateOutcome createOrReuse(PlanRequest request, long accountId, String role,
                                                   String fingerprint) {
        sweepExpired();

        String requestKey = accountId + "|" + request.getRequestId();
        String existingRunId = requestIndex.get(requestKey);
        if (existingRunId != null) {
            RunEntry existing = runs.get(existingRunId);
            if (existing != null) {
                if (!existing.getFingerprint().equals(fingerprint)) {
                    throw AgentApiException.conflict("相同的请求ID携带了不同的请求内容");
                }
                return new CreateOutcome(existing, false);
            }
        }

        String sessionKey = accountId + "|" + request.getMemoryId();
        String activeRunId = sessionIndex.get(sessionKey);
        if (activeRunId != null) {
            RunEntry active = runs.get(activeRunId);
            if (active != null && active.isActive()) {
                throw AgentApiException.conflict("同一会话已有进行中的规划任务，请等待完成或先取消");
            }
            sessionIndex.remove(sessionKey, activeRunId);
        }

        long now = System.currentTimeMillis();
        long windowMs = props.getCreateWindow().toMillis();
        Deque<Long> timestamps = createTimestamps.computeIfAbsent(accountId, key -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMs) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= props.getMaxCreatesPerWindow()) {
                throw AgentApiException.tooManyRequests("新建规划任务过于频繁，请稍后再试");
            }
            timestamps.addLast(now);
        }

        if (activeCount() >= props.getMaxActiveRuns()) {
            throw AgentApiException.tooManyRequests("当前活跃规划任务过多，请稍后再试");
        }
        if (runs.size() >= props.getMaxStoredRuns()) {
            throw AgentApiException.tooManyRequests("保存的规划任务已达上限，请稍后再试");
        }

        String runId = UUID.randomUUID().toString().replace("-", "");
        RunEntry entry = new RunEntry(runId, accountId, role, request.getMemoryId(),
                request.getRequestId(), fingerprint);
        runs.put(runId, entry);
        requestIndex.put(requestKey, runId);
        sessionIndex.put(sessionKey, runId);
        return new CreateOutcome(entry, true);
    }

    /** 按账号查询任务；不属于该账号的任务与不存在同样处理。 */
    public RunEntry findForAccount(String runId, long accountId) {
        sweepExpired();
        RunEntry entry = runId == null ? null : runs.get(runId);
        if (entry == null || entry.getAccountId() != accountId) {
            return null;
        }
        return entry;
    }

    public Collection<RunEntry> all() {
        return runs.values();
    }

    public int activeCount() {
        int count = 0;
        for (RunEntry entry : runs.values()) {
            if (entry.isActive()) {
                count++;
            }
        }
        return count;
    }

    public void markRunning(RunEntry entry) {
        if (entry.isActive() && !entry.isCancelRequested()) {
            entry.setStatus(STATUS_RUNNING);
        }
    }

    /** 写入终态；已处于终态的任务不会被覆盖。 */
    public synchronized void finish(RunEntry entry, String status, String stage, Object result, String error) {
        if (entry.isTerminal()) {
            return;
        }
        entry.setStatus(status);
        entry.setStage(stage);
        entry.setResult(result);
        entry.setError(error);
        entry.setFinishedAtMs(System.currentTimeMillis());
        releaseSession(entry);
    }

    /** 请求取消：排队中的任务立即终态，运行中的任务由执行线程在检查点结束。 */
    public synchronized void requestCancel(RunEntry entry) {
        entry.setCancelRequested(true);
        Future<?> task = entry.getTask();
        if (task != null) {
            task.cancel(true);
        }
        if (STATUS_QUEUED.equals(entry.getStatus())) {
            finish(entry, STATUS_CANCELLED, "已取消", null, null);
        }
    }

    /** 清理终态且超过保留期的任务，并回收索引。 */
    public synchronized void sweepExpired() {
        long now = System.currentTimeMillis();
        long ttlMs = props.getRunTtl().toMillis();
        List<String> expired = new ArrayList<>();
        for (RunEntry entry : runs.values()) {
            if (entry.isTerminal() && now - entry.getCreatedAtMs() > ttlMs) {
                expired.add(entry.getRunId());
            }
        }
        for (String runId : expired) {
            RunEntry entry = runs.remove(runId);
            if (entry == null) {
                continue;
            }
            requestIndex.remove(entry.getAccountId() + "|" + entry.getRequestId(), runId);
            sessionIndex.remove(entry.getAccountId() + "|" + entry.getMemoryId(), runId);
        }
    }

    private void releaseSession(RunEntry entry) {
        sessionIndex.remove(entry.getAccountId() + "|" + entry.getMemoryId(), entry.getRunId());
    }

    /** 供测试与诊断使用：当前账号在窗口内的新建次数。 */
    public int createCountWithinWindow(long accountId) {
        Deque<Long> timestamps = createTimestamps.get(accountId);
        if (timestamps == null) {
            return 0;
        }
        synchronized (timestamps) {
            return timestamps.size();
        }
    }

    public Map<String, Object> diagnostics() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("storedRuns", runs.size());
        summary.put("activeRuns", activeCount());
        return summary;
    }
}
