package com.viyangle.study_tour.filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TraceIdFilter} 的 traceId 生成、透传与清理行为。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("没有传入 X-Request-Id 时生成 16 位 traceId，并写入响应头")
    void generatesTraceIdWhenMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> insideFilter = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> insideFilter.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertNotNull(insideFilter.get());
        assertEquals(16, insideFilter.get().length());
        assertEquals(insideFilter.get(), response.getHeader(TraceIdFilter.TRACE_ID_HEADER));
        assertNull(MDC.get(TraceIdFilter.MDC_KEY), "请求结束后必须清理 MDC");
    }

    @Test
    @DisplayName("透传调用方传入的 X-Request-Id，便于全链路对齐")
    void reusesIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/projects");
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "gateway-abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> insideFilter = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> insideFilter.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertEquals("gateway-abc-123", insideFilter.get());
        assertEquals("gateway-abc-123", response.getHeader(TraceIdFilter.TRACE_ID_HEADER));
    }

    @Test
    @DisplayName("超长或含非法字符的传入值被丢弃，避免日志注入")
    void rejectsUnsafeIncomingTraceId() throws Exception {
        MockHttpServletRequest tooLong = new MockHttpServletRequest("GET", "/projects");
        tooLong.addHeader(TraceIdFilter.TRACE_ID_HEADER, "x".repeat(200));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> insideFilter = new AtomicReference<>();

        filter.doFilter(tooLong, response, (req, res) -> insideFilter.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertNotNull(insideFilter.get());
        assertEquals(16, insideFilter.get().length());

        MockHttpServletRequest injected = new MockHttpServletRequest("GET", "/projects");
        injected.addHeader(TraceIdFilter.TRACE_ID_HEADER, "abc\nforged-line");
        MockHttpServletResponse injectedResponse = new MockHttpServletResponse();
        AtomicReference<String> injectedTrace = new AtomicReference<>();

        filter.doFilter(injected, injectedResponse,
                (req, res) -> injectedTrace.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertNotNull(injectedTrace.get());
        assertTrue(injectedTrace.get().length() == 16, "非法值应被替换为新生成的 traceId");
    }
}
