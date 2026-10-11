package com.viyangle.study_tour.agent.exception;

import lombok.Getter;

/**
 * Agent 接口主动拒绝请求时抛出的异常，响应体固定为 {@code {"error": "..."}}。
 *
 * <p>与仓库其余接口不同：{@code /routes/agent/**} 不包裹在 {@code {code,msg,data}} 中，
 * 状态码通过 {@link #getStatus()} 直接映射到 HTTP 状态。</p>
 */
@Getter
public class AgentApiException extends RuntimeException {

    private final int status;

    public AgentApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static AgentApiException notFound(String message) {
        return new AgentApiException(404, message);
    }

    public static AgentApiException conflict(String message) {
        return new AgentApiException(409, message);
    }

    public static AgentApiException tooManyRequests(String message) {
        return new AgentApiException(429, message);
    }

    public static AgentApiException forbidden(String message) {
        return new AgentApiException(403, message);
    }
}
