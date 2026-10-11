package com.viyangle.study_tour.agent.controller;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.viyangle.study_tour.agent.dto.AgentError;
import com.viyangle.study_tour.agent.exception.AgentApiException;
import com.viyangle.study_tour.agent.exception.AgentValidationException;
import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 接口专用异常处理。
 *
 * <p>与全局 {@code GlobalExceptionHandler} 不同：这里不做 {@code Result} 包装，
 * 401/403/404/409/429 返回 {@code {"error": "..."}}，422 返回 FastAPI 风格的
 * {@code {"detail": [...]}}。限定在 agent 包内，其他接口行为不变。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.viyangle.study_tour.agent.controller")
public class AgentApiExceptionHandler {

    @ExceptionHandler(AgentApiException.class)
    public ResponseEntity<AgentError> handleAgentApi(AgentApiException e) {
        log.warn("Agent 请求被拒绝, status={}, reason={}", e.getStatus(), e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(new AgentError(e.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<AgentError> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new AgentError(e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<AgentError> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new AgentError(e.getMessage()));
    }

    @ExceptionHandler(AgentValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(AgentValidationException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getDetail()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", List.of(unreadableDetail(e))));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentError> handleOther(Exception e) {
        log.error("Agent 接口异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new AgentError("系统繁忙，请稍后再试"));
    }

    private Map<String, Object> unreadableDetail(HttpMessageNotReadableException e) {
        Throwable cause = e.getCause();
        if (cause instanceof InvalidFormatException invalidFormat) {
            List<Object> loc = new ArrayList<>();
            loc.add("body");
            for (JsonMappingException.Reference reference : invalidFormat.getPath()) {
                if (reference.getFieldName() != null) {
                    loc.add(reference.getFieldName());
                } else if (reference.getIndex() >= 0) {
                    loc.add(reference.getIndex());
                }
            }
            String type = parsingTypeOf(invalidFormat.getTargetType());
            String message = switch (type) {
                case "int_parsing" -> "Input should be a valid integer, unable to parse value";
                case "float_parsing" -> "Input should be a valid number, unable to parse value";
                case "bool_parsing" -> "Input should be a valid boolean, unable to parse value";
                default -> "Input should be a valid value";
            };
            return detail(type, loc, message, String.valueOf(invalidFormat.getValue()), null);
        }
        if (e.getMessage() != null && e.getMessage().contains("Required request body is missing")) {
            return detail("missing", List.of("body"), "Field required", null, null);
        }
        return detail("json_invalid", List.of("body"), "JSON decode error", null, null);
    }

    private String parsingTypeOf(Class<?> targetType) {
        if (targetType == null) {
            return "value_error";
        }
        String name = targetType.getSimpleName();
        return switch (name) {
            case "Long", "Integer", "Short", "Byte" -> "int_parsing";
            case "Double", "Float", "BigDecimal" -> "float_parsing";
            case "Boolean" -> "bool_parsing";
            default -> "value_error";
        };
    }

    private Map<String, Object> detail(String type, List<Object> loc, String msg,
                                       Object input, Map<String, ?> ctx) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("loc", new ArrayList<>(loc));
        entry.put("msg", msg);
        entry.put("input", input);
        if (ctx != null) {
            entry.put("ctx", ctx);
        }
        return entry;
    }
}
