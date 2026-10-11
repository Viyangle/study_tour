package com.viyangle.study_tour.agent.exception;

/** 规划链路本身失败（模型、地图鉴权、查询限额等），任务以 failed 终态返回。 */
public class AgentPlanningException extends RuntimeException {
    public AgentPlanningException(String message) {
        super(message);
    }

    public AgentPlanningException(String message, Throwable cause) {
        super(message, cause);
    }
}
