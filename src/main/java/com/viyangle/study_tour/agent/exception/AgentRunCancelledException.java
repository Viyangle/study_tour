package com.viyangle.study_tour.agent.exception;

/** 执行过程中检测到取消请求，用于把任务置为 cancelled 终态。 */
public class AgentRunCancelledException extends RuntimeException {
    public AgentRunCancelledException() {
        super("任务已取消");
    }
}
