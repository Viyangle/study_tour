package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.config.AgentProperties;
import com.viyangle.study_tour.agent.exception.AgentPlanningException;
import com.viyangle.study_tour.agent.exception.AgentRunCancelledException;

import java.util.function.BooleanSupplier;

/** 一个 run 内共享；工具顺序由模型决定，额度与终止条件由服务端决定。 */
public final class AgentExecutionBudget {
    private final long deadlineMs;
    private final BooleanSupplier cancelled;
    private final int maxToolCalls;
    private int toolCalls;
    private int remainingDirections;

    public AgentExecutionBudget(AgentProperties props, long createdAtMs, BooleanSupplier cancelled) {
        this.deadlineMs = createdAtMs + props.getExecutionTimeout().toMillis();
        this.cancelled = cancelled;
        this.maxToolCalls = Math.max(1, props.getMaxToolCalls());
        this.remainingDirections = Math.max(0, props.getMaxDirectionRequests());
    }

    public void checkActive() {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
            throw new AgentRunCancelledException();
        }
        if (System.currentTimeMillis() >= deadlineMs) {
            throw new AgentPlanningException("整轮执行超时");
        }
    }

    public void consumeToolCall() {
        checkActive();
        if (++toolCalls > maxToolCalls) {
            throw new AgentPlanningException("已达到本轮工具调用上限");
        }
    }

    public boolean takeDirectionRequest() {
        checkActive();
        if (remainingDirections <= 0) {
            return false;
        }
        remainingDirections--;
        return true;
    }
}
