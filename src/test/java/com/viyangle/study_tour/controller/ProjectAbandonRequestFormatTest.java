package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.service.KnowledgeGraphRecommendService;
import com.viyangle.study_tour.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 核实 {@code POST /projects/{id}/abandon} 到底接受哪些 reason 传法。
 *
 * <p>结论会写进前端联调文档，因此这里用真实 Spring MVC 绑定 + mock service 验证，
 * 而不是靠读代码推测。</p>
 */
class ProjectAbandonRequestFormatTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long ACCOUNT_ID = 7L;
    private static final String REASON = "临时家里有事，无法带队";

    private ProjectService projectService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        ProjectController controller = new ProjectController();
        ReflectionTestUtils.setField(controller, "projectService", projectService);
        ReflectionTestUtils.setField(controller, "recommendService",
                mock(KnowledgeGraphRecommendService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        authenticate(ACCOUNT_ID, "LEADER");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Long accountId, String role) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId.toString(), null, List.of());
        Map<String, Object> details = new HashMap<>();
        details.put("accountId", accountId);
        details.put("role", role);
        authentication.setDetails(details);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    @Test
    void acceptsReasonAsQueryParameter() throws Exception {
        mockMvc.perform(post("/projects/" + PROJECT_ID + "/abandon").param("reason", REASON))
                .andExpect(status().isOk());

        verify(projectService).abandonProject(eq(PROJECT_ID), eq(ACCOUNT_ID), eq(REASON));
    }

    @Test
    void acceptsReasonInJsonBody() throws Exception {
        mockMvc.perform(post("/projects/" + PROJECT_ID + "/abandon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isOk());

        verify(projectService).abandonProject(eq(PROJECT_ID), eq(ACCOUNT_ID), eq(REASON));
    }

    @Test
    void acceptsReasonInFormEncodedBody() throws Exception {
        mockMvc.perform(post("/projects/" + PROJECT_ID + "/abandon")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("reason", REASON))
                .andExpect(status().isOk());

        verify(projectService).abandonProject(eq(PROJECT_ID), eq(ACCOUNT_ID), eq(REASON));
    }

    @Test
    void passesNullReasonWhenNothingProvided() throws Exception {
        mockMvc.perform(post("/projects/" + PROJECT_ID + "/abandon"))
                .andExpect(status().isOk());

        verify(projectService).abandonProject(eq(PROJECT_ID), eq(ACCOUNT_ID), isNull());
        verifyNoMoreInteractions(projectService);
    }

    @Test
    void passesNullReasonForBodyWithoutReasonFieldOrWithBrokenJson() throws Exception {
        mockMvc.perform(post("/projects/" + PROJECT_ID + "/abandon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"other\":1}"))
                .andExpect(status().isOk());
        verify(projectService).abandonProject(eq(PROJECT_ID), eq(ACCOUNT_ID), isNull());

        org.mockito.Mockito.clearInvocations(projectService);

        mockMvc.perform(post("/projects/" + PROJECT_ID + "/abandon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":"))
                .andExpect(status().isOk());
        verify(projectService).abandonProject(eq(PROJECT_ID), eq(ACCOUNT_ID), isNull());
    }
}
