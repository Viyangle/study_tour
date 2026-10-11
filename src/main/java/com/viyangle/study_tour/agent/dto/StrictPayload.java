package com.viyangle.study_tour.agent.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 严格请求对象基类。
 *
 * <p>{@code docs/API.md} 要求输入对象中的未知字段会被拒绝（422），而 Spring Boot 默认的
 * ObjectMapper 会静默忽略未知字段。这里用 {@link JsonAnySetter} 把未知字段收集起来，
 * 由 {@code PlanRequestValidator} 统一转换成 FastAPI 风格的 {@code extra_forbidden} 错误，
 * 这样不依赖全局 Jackson 配置，也能一次性报告全部未知字段。</p>
 */
public abstract class StrictPayload {

    private final Map<String, Object> extraFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void putExtraField(String name, Object value) {
        extraFields.put(name, value);
    }

    @JsonIgnore
    public Map<String, Object> getExtraFields() {
        return extraFields;
    }
}
