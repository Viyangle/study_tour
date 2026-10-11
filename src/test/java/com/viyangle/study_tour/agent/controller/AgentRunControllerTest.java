package com.viyangle.study_tour.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.agent.config.AgentProperties;
import com.viyangle.study_tour.agent.service.AgentRunExecutor;
import com.viyangle.study_tour.agent.service.AgentRunStore;
import com.viyangle.study_tour.agent.service.PlanRequestValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent 接口契约：直接 JSON 响应、错误体 {@code {"error"}}、422 detail、
 * 幂等、账号隔离、取消与限流。
 */
class AgentRunControllerTest {

    private static final String VALID_BODY = """
            {
              "requestId": "req-1",
              "memoryId": "mem-1",
              "accountId": 1,
              "message": "南京亲子历史研学，控制在4小时"
            }
            """;

    private MockMvc mockMvc;
    private AgentRunStore store;
    private AgentRunExecutor executor;
    private AgentProperties props;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        props.setMaxActiveRuns(50);
        props.setMaxStoredRuns(500);
        props.setMaxCreatesPerWindow(6);
        store = new AgentRunStore(props);
        executor = mock(AgentRunExecutor.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentRunController controller =
                new AgentRunController(store, executor, new PlanRequestValidator(), objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller, new HealthController())
                .setControllerAdvice(new AgentApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        authenticate(1L, "USER");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Long accountId, String role) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(String.valueOf(accountId), null, List.of());
        Map<String, Object> details = new HashMap<>();
        details.put("accountId", accountId);
        details.put("role", role);
        authentication.setDetails(details);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    @Test
    void healthIsPublicAndReturnsBareJson() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.version").value("1.0"));
    }

    @Test
    void rejectsRequestWithoutLogin() throws Exception {
        SecurityContextHolder.clearContext();
        mockMvc.perform(post("/routes/agent/runs").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或token无效"));
        verifyNoMoreInteractions(executor);
    }

    @Test
    void rejectsAccountIdMismatch() throws Exception {
        mockMvc.perform(post("/routes/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"accountId\": 1", "\"accountId\": 2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("账号与登录身份不一致"));
    }

    @Test
    void rejectsUnknownFieldWithFastApiStyleDetail() throws Exception {
        mockMvc.perform(post("/routes/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"accountId\": 1,", "\"accountId\": 1, \"foo\": 1,")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail[0].type").value("extra_forbidden"))
                .andExpect(jsonPath("$.detail[0].loc[0]").value("body"))
                .andExpect(jsonPath("$.detail[0].loc[1]").value("foo"))
                .andExpect(jsonPath("$.detail[0].msg").value("Extra inputs are not permitted"));
    }

    @Test
    void rejectsMissingBodyAndMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/routes/agent/runs").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail[0].type").value("missing"));

        mockMvc.perform(post("/routes/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"req-1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("memoryId")));
    }

    @Test
    void acceptsValidRequestAndReturnsQueuedRun() throws Exception {
        mockMvc.perform(post("/routes/agent/runs").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(
                        org.hamcrest.Matchers.matchesPattern("^[a-f0-9]{32}$")))
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.stage").value("等待规划"))
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.result").value(nullValue()))
                .andExpect(jsonPath("$.error").value(nullValue()));
        verify(executor).submit(any(), any(), anyLong(), anyString());
    }

    @Test
    void replaysIdenticalRequestIdempotently() throws Exception {
        String first = mockMvc.perform(post("/routes/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = new ObjectMapper().readTree(first).get("runId").asText();

        mockMvc.perform(post("/routes/agent/runs").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(runId));
        verify(executor).submit(any(), any(), anyLong(), anyString());
    }

    @Test
    void rejectsSameRequestIdWithDifferentContent() throws Exception {
        mockMvc.perform(post("/routes/agent/runs").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/routes/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("控制在4小时", "控制在2小时")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("相同的请求ID携带了不同的请求内容"));
    }

    @Test
    void rejectsSecondActiveRunForSameSession() throws Exception {
        mockMvc.perform(post("/routes/agent/runs").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/routes/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"requestId\": \"req-1\"", "\"requestId\": \"req-2\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "同一会话已有进行中的规划任务，请等待完成或先取消"));
    }

    @Test
    void limitsCreateRatePerAccount() throws Exception {
        props.setMaxCreatesPerWindow(1);
        mockMvc.perform(post("/routes/agent/runs").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/routes/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY
                                .replace("\"requestId\": \"req-1\"", "\"requestId\": \"req-2\"")
                                .replace("\"memoryId\": \"mem-1\"", "\"memoryId\": \"mem-2\"")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("新建规划任务过于频繁，请稍后再试"));
    }

    @Test
    void queriesAndCancelsRunOfCurrentAccount() throws Exception {
        String body = mockMvc.perform(post("/routes/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = new ObjectMapper().readTree(body).get("runId").asText();

        mockMvc.perform(get("/routes/agent/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.status").value("queued"));

        mockMvc.perform(delete("/routes/agent/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.stage").value("已取消"));
    }

    @Test
    void returnsNotFoundForOtherAccountAndUnknownRun() throws Exception {
        String body = mockMvc.perform(post("/routes/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = new ObjectMapper().readTree(body).get("runId").asText();

        authenticate(2L, "USER");
        mockMvc.perform(get("/routes/agent/runs/" + runId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("任务不存在"));

        authenticate(1L, "USER");
        mockMvc.perform(get("/routes/agent/runs/ffffffffffffffffffffffffffffffff"))
                .andExpect(status().isNotFound());
    }
}
