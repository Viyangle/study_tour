package com.viyangle.study_tour.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Agent 路径的 401/403 响应体为 {@code {"error"}}；
 * 仓库其他路径仍保持 {@code {code,msg,data}}。
 */
class SecurityExceptionHandlerAgentPathTest {

    private final SecurityExceptionHandler handler = new SecurityExceptionHandler(new ObjectMapper());

    @Test
    void writesBareErrorForAgentPathOnUnauthorized() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/routes/agent/runs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.commence(request, response, new InsufficientAuthenticationException("no token"));

        assertEquals(401, response.getStatus());
        assertEquals("{\"error\":\"未登录或token无效\"}", response.getContentAsString());
    }

    @Test
    void writesBareErrorForAgentPathOnAccessDenied() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/routes/agent/runs/abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.handle(request, response, new AccessDeniedException("denied"));

        assertEquals(403, response.getStatus());
        assertEquals("{\"error\":\"权限不足\"}", response.getContentAsString());
    }

    @Test
    void keepsResultEnvelopeForOtherPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/routes/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.commence(request, response, new InsufficientAuthenticationException("no token"));

        assertEquals(401, response.getStatus());
        assertEquals("{\"code\":0,\"msg\":\"未登录或token无效\",\"data\":null}",
                response.getContentAsString());
    }
}
