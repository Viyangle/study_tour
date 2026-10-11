package com.viyangle.study_tour.security;

import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.config.InterceptorConfig;
import com.viyangle.study_tour.config.SecurityConfig;
import com.viyangle.study_tour.controller.AttractionController;
import com.viyangle.study_tour.controller.AccountController;
import com.viyangle.study_tour.controller.LoginController;
import com.viyangle.study_tour.service.AccountService;
import com.viyangle.study_tour.exception.SecurityExceptionHandler;
import com.viyangle.study_tour.filter.JwtAuthFilter;
import com.viyangle.study_tour.interceptor.LogInterceptor;
import com.viyangle.study_tour.interceptor.PermissionInterceptor;
import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AttractionService;
import com.viyangle.study_tour.service.AttractionSyncService;
import com.viyangle.study_tour.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SecurityAuthorizationIntegrationTest.TestConfig.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "app.security.enabled=true",
        "jwt.secret=test-only-signing-key-not-for-production-1234567890",
        "jwt.expiration=86400000",
        "jwt.refresh-expiration=604800000"
})
class SecurityAuthorizationIntegrationTest {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String USER_TOKEN = "user-token";
    private static final String LEADER_TOKEN = "leader-token";
    private static final String BOTH_TOKEN = "both-token";
    private static final String DISABLED_TOKEN = "disabled-token";

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.web.context.WebApplicationContext webApplicationContext;

    @org.springframework.beans.factory.annotation.Autowired
    private SecurityFilterChain securityFilterChain;

    private MockMvc mockMvc;

    @org.springframework.beans.factory.annotation.Autowired
    private JwtUtil jwtUtil;

    @org.springframework.beans.factory.annotation.Autowired
    private AccountMapper accountMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private AttractionSyncService attractionSyncService;

    @org.springframework.beans.factory.annotation.Autowired
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        reset(jwtUtil, accountMapper, attractionSyncService, accountService);
        SecurityContextHolder.clearContext();
        FilterChainProxy springSecurityFilterChain = new FilterChainProxy(securityFilterChain);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userCannotReindexAttractions() throws Exception {
        stubValidToken(USER_TOKEN, 100L, "USER");

        mockMvc.perform(post("/attractions/reindex")
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isForbidden());

        verify(attractionSyncService, never()).reindexAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "LEADER", "BOTH"})
    void ordinaryRolesCanReachTheirOwnRoleChangeEndpoint(String currentRole) throws Exception {
        stubValidToken(USER_TOKEN, 100L, currentRole);
        Account updated = new Account();
        updated.setId(100L);
        updated.setRole("BOTH");
        when(accountService.updateRole(100L, "BOTH")).thenReturn(updated);

        mockMvc.perform(put("/accounts/100/role")
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"BOTH\"}"))
                .andExpect(status().isOk());
        verify(accountService).updateRole(100L, "BOTH");
    }

    @Test
    void adminCanReachAttractionReindexHandler() throws Exception {
        stubValidToken(ADMIN_TOKEN, 101L, "ADMIN");
        when(attractionSyncService.reindexAll()).thenReturn(3);

        mockMvc.perform(post("/attractions/reindex")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        verify(attractionSyncService).reindexAll();
    }

    @Test
    void unauthenticatedProtectedRequestReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/attractions/reindex"))
                .andExpect(status().isUnauthorized());

        verify(attractionSyncService, never()).reindexAll();
    }

    @Test
    void disabledAccountReturnsUnauthorized() throws Exception {
        stubToken(DISABLED_TOKEN, 104L, "ADMIN", "ADMIN", 0);

        mockMvc.perform(post("/attractions/reindex")
                        .header("Authorization", "Bearer " + DISABLED_TOKEN))
                .andExpect(status().isUnauthorized());

        verify(attractionSyncService, never()).reindexAll();
    }

    @Test
    void identityEndpointReturnsTokenAccountId() throws Exception {
        stubValidToken(USER_TOKEN, 100L, "USER");

        mockMvc.perform(get("/login/me")
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.accountId").value(100))
                .andExpect(jsonPath("$.data.role").doesNotExist());
    }

    @Test
    void identityEndpointWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/login/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenCannotAuthenticateIdentityEndpoint() throws Exception {
        stubToken("refresh-token", 100L, null, "USER", 1);

        mockMvc.perform(get("/login/me")
                        .header("Authorization", "Bearer refresh-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledAccountCannotUseIdentityEndpoint() throws Exception {
        stubToken(DISABLED_TOKEN, 104L, "ADMIN", "ADMIN", 0);

        mockMvc.perform(get("/login/me")
                        .header("Authorization", "Bearer " + DISABLED_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void identityEndpointRejectsLowercaseBearerPrefix() throws Exception {
        stubValidToken(USER_TOKEN, 100L, "USER");

        mockMvc.perform(get("/login/me")
                        .header("Authorization", "bearer " + USER_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oldAdminTokenCannotOverrideCurrentDatabaseUserRole() throws Exception {
        stubToken("old-admin-token", 105L, "ADMIN", "USER", 1);

        mockMvc.perform(post("/attractions/reindex")
                        .header("Authorization", "Bearer old-admin-token"))
                .andExpect(status().isForbidden());

        verify(attractionSyncService, never()).reindexAll();
    }

    @Test
    void bothRoleCanUseUserAndLeaderEndpoints() throws Exception {
        stubValidToken(BOTH_TOKEN, 102L, "BOTH");

        mockMvc.perform(get("/probe/user")
                        .header("Authorization", "Bearer " + BOTH_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(get("/probe/leader")
                        .header("Authorization", "Bearer " + BOTH_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void classAndMethodRequireRoleAnnotationsAreApplied() throws Exception {
        stubValidToken(LEADER_TOKEN, 103L, "LEADER");

        mockMvc.perform(get("/probe/class")
                        .header("Authorization", "Bearer " + LEADER_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(get("/probe/method")
                        .header("Authorization", "Bearer " + LEADER_TOKEN))
                .andExpect(status().isForbidden());

        stubValidToken(USER_TOKEN, 100L, "USER");
        mockMvc.perform(get("/probe/method")
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isOk());
    }

    private void stubValidToken(String token, Long accountId, String role) {
        stubToken(token, accountId, role, role, 1);
    }

    private void stubToken(String token,
                           Long accountId,
                           String tokenRole,
                           String databaseRole,
                           int status) {
        String subject = accountId.toString();
        when(jwtUtil.extractUsername(token)).thenReturn(subject);
        when(jwtUtil.validateToken(token, subject)).thenReturn(true);
        when(jwtUtil.extractAccountId(token)).thenReturn(accountId);
        when(jwtUtil.extractRole(token)).thenReturn(tokenRole);

        Account account = new Account();
        account.setId(accountId);
        account.setRole(databaseRole);
        account.setStatus(status);
        when(accountMapper.selectById(accountId)).thenReturn(account);
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        JwtUtil jwtUtil() {
            return mock(JwtUtil.class);
        }

        @Bean
        AccountMapper accountMapper() {
            return mock(AccountMapper.class);
        }

        @Bean
        JwtAuthFilter jwtAuthFilter() {
            return new JwtAuthFilter();
        }

        @Bean
        PermissionInterceptor permissionInterceptor() {
            return new PermissionInterceptor();
        }

        @Bean
        LogInterceptor logInterceptor(JwtUtil jwtUtil) {
            return new LogInterceptor(jwtUtil);
        }

        @Bean
        SecurityExceptionHandler securityExceptionHandler(ObjectMapper objectMapper) {
            return new SecurityExceptionHandler(objectMapper);
        }

        @Bean
        AttractionService attractionService() {
            return mock(AttractionService.class);
        }

        @Bean
        AttractionSyncService attractionSyncService() {
            return mock(AttractionSyncService.class);
        }

        @Bean
        AttractionController attractionController() {
            return new AttractionController();
        }

        @Bean
        AccountService accountService() {
            return mock(AccountService.class);
        }

        @Bean
        AccountController accountController() {
            return new AccountController();
        }

        @Bean
        LoginController loginController() {
            return new LoginController();
        }

        @Bean
        InterceptorConfig interceptorConfig(PermissionInterceptor permissionInterceptor,
                                             LogInterceptor logInterceptor) {
            return new InterceptorConfig(permissionInterceptor, logInterceptor);
        }

        @Bean
        RoleProbeController roleProbeController() {
            return new RoleProbeController();
        }
    }

    @RestController
    @RequestMapping("/probe")
    @RequireRole({"LEADER"})
    static class RoleProbeController {

        @GetMapping("/class")
        public Result classProtected() {
            return Result.success();
        }

        @GetMapping("/method")
        @RequireRole({"USER"})
        public Result methodProtected() {
            return Result.success();
        }

        @GetMapping("/user")
        @RequireRole({"USER"})
        public Result userProtected() {
            return Result.success();
        }

        @GetMapping("/leader")
        public Result leaderProtected() {
            return Result.success();
        }
    }
}
