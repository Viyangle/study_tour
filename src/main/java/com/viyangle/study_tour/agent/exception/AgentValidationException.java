package com.viyangle.study_tour.agent.exception;

import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 请求体校验失败，响应为 HTTP 422 + FastAPI 风格 {@code {"detail": [...]}}。
 *
 * <p>文档要求 422 使用 FastAPI 参数校验格式；Spring 默认返回 400 且结构不同，
 * 因此这里自行收集 detail 条目。</p>
 */
@Getter
public class AgentValidationException extends RuntimeException {

    private final transient List<Map<String, Object>> detail;

    public AgentValidationException(List<Map<String, Object>> detail) {
        super("validation error");
        this.detail = detail;
    }
}
