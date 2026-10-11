package com.viyangle.study_tour.filter;

import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.utils.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterSecurityTest {

    private static final String TOKEN = "token";

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtAuthFilter filter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void disabledAccountCannotAuthenticateWithAnOtherwiseValidAccessToken() throws Exception {
        stubAccessToken("7", 7L, "ADMIN");
        when(accountMapper.selectById(7L)).thenReturn(account(7L, "ADMIN", 0));

        invokeFilter();

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void deletedAccountCannotAuthenticateWithAnOtherwiseValidAccessToken() throws Exception {
        stubAccessToken("7", 7L, "USER");
        when(accountMapper.selectById(7L)).thenReturn(null);

        invokeFilter();

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void currentDatabaseRoleReplacesTheStaleRoleInAnAccessToken() throws Exception {
        stubAccessToken("7", 7L, "ADMIN");
        Account currentAccount = account(7L, "USER", 1);
        when(accountMapper.selectById(7L)).thenReturn(currentAccount);

        invokeFilter();

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(authentication != null && authentication.isAuthenticated());
        assertEquals("7", authentication.getName());
        Map<?, ?> details = (Map<?, ?>) authentication.getDetails();
        assertEquals(7L, details.get("accountId"));
        assertEquals("USER", details.get("role"));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void refreshTokenWithoutRoleClaimIsNotAcceptedAsAnAccessToken() throws Exception {
        stubAccessToken("7", 7L, null);

        invokeFilter();

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(accountMapper, never()).selectById(7L);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void validEnabledUserIsAuthenticatedWithTheDatabaseRole() throws Exception {
        stubAccessToken("7", 7L, "USER");
        Account currentAccount = account(7L, "USER", 1);
        when(accountMapper.selectById(7L)).thenReturn(currentAccount);

        invokeFilter();

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(authentication != null && authentication.isAuthenticated());
        assertEquals("USER", ((Map<?, ?>) authentication.getDetails()).get("role"));
        assertSame(authentication, SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void subjectAndAccountIdMustMatch() throws Exception {
        stubAccessToken("8", 7L, "USER");

        invokeFilter();

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(accountMapper, never()).selectById(7L);
        verify(filterChain).doFilter(request, response);
    }

    private void stubAccessToken(String subject, Long accountId, String role) {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + TOKEN);
        when(jwtUtil.extractUsername(TOKEN)).thenReturn(subject);
        when(jwtUtil.validateToken(TOKEN, subject)).thenReturn(true);
        when(jwtUtil.extractAccountId(TOKEN)).thenReturn(accountId);
        when(jwtUtil.extractRole(TOKEN)).thenReturn(role);
    }

    private void invokeFilter() throws ServletException, IOException {
        filter.doFilterInternal(request, response, filterChain);
    }

    private Account account(Long id, String role, int status) {
        Account account = new Account();
        account.setId(id);
        account.setRole(role);
        account.setStatus(status);
        return account;
    }
}
