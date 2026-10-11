package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AccountService;
import com.viyangle.study_tour.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginControllerSecurityTest {
    private final JwtUtil jwt = new JwtUtil();
    private final AccountService accounts = mock(AccountService.class);
    private final LoginController controller = new LoginController();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwt, "secretKey", "test-only-signing-key-not-for-production-1234567890");
        ReflectionTestUtils.setField(jwt, "expiration", 172800000L);
        ReflectionTestUtils.setField(jwt, "refreshExpiration", 604800000L);
        ReflectionTestUtils.setField(controller, "jwtUtil", jwt);
        ReflectionTestUtils.setField(controller, "accountService", accounts);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void accessTokenCannotBeUsedAsRefreshToken() {
        Result result = controller.refreshToken(jwt.generateToken(1L, "USER"));
        assertEquals(0, result.getCode());
        verifyNoInteractions(accounts);
    }

    @Test
    void disabledAccountCannotRefreshTokens() {
        Account account = new Account();
        account.setId(1L);
        account.setStatus(0);
        when(accounts.getById(1L)).thenReturn(account);
        Result result = controller.refreshToken(jwt.generateRefreshToken(1L));
        assertEquals(0, result.getCode());
    }

    @Test
    void activeAccountRefreshUsesCurrentDatabaseRole() {
        Account account = new Account();
        account.setId(1L);
        account.setStatus(1);
        account.setRole("USER");
        when(accounts.getById(1L)).thenReturn(account);
        Result result = controller.refreshToken(jwt.generateRefreshToken(1L));
        assertEquals(1, result.getCode());
        String token = (String) ((Map<?, ?>) result.getData()).get("token");
        assertEquals("USER", jwt.extractRole(token));
        assertEquals(1L, jwt.extractAccountId(token));
    }

    @Test
    void currentAccountRequiresAuthentication() {
        ResponseEntity<Result> response = controller.currentAccount();

        assertEquals(401, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().getCode());
    }

    @Test
    void currentAccountReturnsAuthenticatedAccountIdOnly() {
        authenticate(7L, "LEADER");

        ResponseEntity<Result> response = controller.currentAccount();

        assertEquals(200, response.getStatusCode().value());
        Result result = response.getBody();
        assertNotNull(result);
        assertEquals(1, result.getCode());
        Map<?, ?> data = (Map<?, ?>) result.getData();
        assertEquals(7L, data.get("accountId"));
        // 只返回文档列出的 accountId，避免调用方使用严格响应模型时被拒
        assertEquals(1, data.size());
        // 身份只来自 token，接口不接受调用方传入的账号
        verifyNoInteractions(accounts);
    }

    private void authenticate(Long accountId, String role) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId.toString(), null, List.of());
        Map<String, Object> details = new HashMap<>();
        details.put("accountId", accountId);
        details.put("role", role);
        authentication.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
