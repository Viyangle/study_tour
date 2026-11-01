package com.viyangle.study_tour.agent.dto;

/** 无需生成路线的普通回复；不会要求客户端应用路线。 */
public record AgentReply(String kind, String message) {
    public AgentReply(String message) {
        this("answer", message);
    }
}
