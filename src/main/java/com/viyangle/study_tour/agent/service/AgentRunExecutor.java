package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.config.AgentProperties;
import com.viyangle.study_tour.agent.dto.Clarification;
import com.viyangle.study_tour.agent.dto.PlanRequest;
import com.viyangle.study_tour.agent.dto.ToolEvent;
import com.viyangle.study_tour.agent.exception.AgentRunCancelledException;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.service.AiRoutePlanningService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 后台执行一轮规划。
 *
 * <p>复用既有 {@link AiRoutePlanningService}（向量/图谱召回 + 模型），在其前后写入工具事件、
 * 装配方案，并处理取消与整轮超时。既有规划服务内部会读取登录身份来隔离会话状态，
 * 因此这里在后台线程上传播认证上下文，避免额外修改那个服务。</p>
 */
@Slf4j
@Component
public class AgentRunExecutor {

    private final AgentRunStore store;
    private final AiRoutePlanningService planningService;
    private final AgentPlanAssembler assembler;
    private final AgentProperties props;

    private volatile ExecutorService pool;
    private volatile ScheduledExecutorService watchdog;

    public AgentRunExecutor(AgentRunStore store,
                            AiRoutePlanningService planningService,
                            AgentPlanAssembler assembler,
                            AgentProperties props) {
        this.store = store;
        this.planningService = planningService;
        this.assembler = assembler;
        this.props = props;
    }

    @PostConstruct
    void init() {
        ensureStarted();
    }

    @PreDestroy
    void shutdown() {
        ExecutorService currentPool = pool;
        if (currentPool != null) {
            currentPool.shutdownNow();
        }
        ScheduledExecutorService currentWatchdog = watchdog;
        if (currentWatchdog != null) {
            currentWatchdog.shutdownNow();
        }
    }

    private synchronized void ensureStarted() {
        if (pool == null) {
            pool = Executors.newFixedThreadPool(Math.max(1, props.getWorkerThreads()), runnable -> {
                Thread thread = new Thread(runnable, "agent-run-worker");
                thread.setDaemon(true);
                return thread;
            });
            watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "agent-run-watchdog");
                thread.setDaemon(true);
                return thread;
            });
            long interval = Math.max(1000L, props.getSweepInterval().toMillis());
            watchdog.scheduleWithFixedDelay(this::watchdogTick, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    /** 提交后台执行；返回的 Future 用于取消。 */
    public Future<?> submit(AgentRunStore.RunEntry entry, PlanRequest request, long accountId, String role) {
        ensureStarted();
        Future<?> future = pool.submit(() -> execute(entry, request, accountId, role));
        entry.setTask(future);
        return future;
    }

    private void execute(AgentRunStore.RunEntry entry, PlanRequest request, long accountId, String role) {
        propagateAuthentication(accountId, role);
        try {
            store.markRunning(entry);
            checkCancelled(entry);
            entry.setStage("理解需求");
            addEvent(entry, ToolEvent.of(null, "理解需求", "running", null));

            checkCancelled(entry);
            entry.setStage("检索候选地点");
            long searchStartedAt = System.nanoTime();
            addEvent(entry, ToolEvent.of("search_places", "检索候选地点", "running", null));
            Clarification earlyClarification = null;
            AIRoutePlan aiPlan = null;
            try {
                String plannerInput = PlannerInputBuilder.build(request);
                aiPlan = planningService.planRouteV2(request.getMemoryId(), plannerInput, accountId);
            } catch (IllegalArgumentException e) {
                if (insufficientCandidates(e)) {
                    earlyClarification = assembler.clarificationForMissingContext(request,
                            "需要先确认出行城市和可用时长，才能给出方案。");
                } else {
                    throw e;
                }
            }
            addEvent(entry, ToolEvent.of("search_places", "检索候选地点",
                    earlyClarification == null ? "completed" : "needs_revision", elapsedMs(searchStartedAt)));

            if (earlyClarification != null) {
                addEvent(entry, ToolEvent.of("ask_user", "等待你补充需求", "completed", null));
                store.finish(entry, AgentRunStore.STATUS_COMPLETED, "等待你补充需求", earlyClarification, null);
                return;
            }

            checkCancelled(entry);
            entry.setStage("校验行程与约束");
            AgentPlanAssembler.Outcome outcome = assembler.assemble(request, aiPlan,
                    event -> addEvent(entry, event),
                    () -> entry.isCancelRequested() || Thread.currentThread().isInterrupted());
            checkCancelled(entry);

            if (outcome.getClarification() != null) {
                store.finish(entry, AgentRunStore.STATUS_COMPLETED, "等待你补充需求",
                        outcome.getClarification(), null);
            } else {
                store.finish(entry, AgentRunStore.STATUS_COMPLETED, "方案已完成", outcome.getPlan(), null);
            }
        } catch (AgentRunCancelledException e) {
            store.finish(entry, AgentRunStore.STATUS_CANCELLED, "已取消", null, null);
        } catch (Exception e) {
            if (entry.isCancelRequested()) {
                store.finish(entry, AgentRunStore.STATUS_CANCELLED, "已取消", null, null);
                return;
            }
            log.error("Agent 规划任务失败, runId={}, memoryId={}", entry.getRunId(), entry.getMemoryId(), e);
            store.finish(entry, AgentRunStore.STATUS_FAILED, "规划失败", null, errorText(e));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean insufficientCandidates(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("Candidate POIs are not enough") || message.contains("候选景点");
    }

    private void propagateAuthentication(long accountId, String role) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(String.valueOf(accountId), null, List.of());
        Map<String, Object> details = new HashMap<>();
        details.put("accountId", accountId);
        details.put("role", role);
        authentication.setDetails(details);
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private void checkCancelled(AgentRunStore.RunEntry entry) {
        if (entry.isCancelRequested() || Thread.currentThread().isInterrupted()) {
            throw new AgentRunCancelledException();
        }
    }

    private void addEvent(AgentRunStore.RunEntry entry, ToolEvent event) {
        entry.addEvent(event, props.getMaxEvents());
    }

    private String errorText(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message;
    }

    private void watchdogTick() {
        try {
            long timeoutMs = props.getExecutionTimeout().toMillis();
            long now = System.currentTimeMillis();
            for (AgentRunStore.RunEntry entry : store.all()) {
                if (entry.isActive() && now - entry.getCreatedAtMs() > timeoutMs) {
                    log.warn("Agent 规划任务超过整轮上限，强制结束, runId={}", entry.getRunId());
                    Future<?> task = entry.getTask();
                    if (task != null) {
                        task.cancel(true);
                    }
                    store.finish(entry, AgentRunStore.STATUS_FAILED, "规划失败", null,
                            "整轮执行超时（超过 " + props.getExecutionTimeout().toSeconds() + " 秒）");
                }
            }
            store.sweepExpired();
        } catch (Exception e) {
            log.warn("Agent 守护任务异常: {}", e.getMessage());
        }
    }

    private static Integer elapsedMs(long startedAtNanos) {
        long millis = (System.nanoTime() - startedAtNanos) / 1_000_000;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, millis));
    }
}
