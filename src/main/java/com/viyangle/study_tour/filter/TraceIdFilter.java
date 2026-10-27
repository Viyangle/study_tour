package com.viyangle.study_tour.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求分配 traceId，写入 MDC 与响应头，便于把一次请求的多行日志串起来。
 *
 * <p>优先复用调用方传入的 {@code X-Request-Id}，方便前端、网关与后端日志对齐；
 * 没有传或格式不合法时生成 16 位随机串。请求结束后清理 MDC，避免线程复用导致串号。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";

    private static final int MAX_TRACE_ID_LENGTH = 64;
    private static final int GENERATED_LENGTH = 16;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveTraceId(String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return generateTraceId();
        }
        String trimmed = incoming.trim();
        if (trimmed.length() > MAX_TRACE_ID_LENGTH || !trimmed.matches("[A-Za-z0-9._:-]+")) {
            return generateTraceId();
        }
        return trimmed;
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, GENERATED_LENGTH);
    }
}
