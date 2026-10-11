package com.viyangle.study_tour.filter;

import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.utils.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Authenticates access JWTs and loads the current account state from the
 * database before putting an authentication into the security context.
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AccountMapper accountMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String token = getTokenFromRequest(request);
            if (StringUtils.hasText(token)
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                authenticateAccessToken(token);
            }
        } catch (Exception e) {
            // Invalid tokens must not prevent public endpoints from being served.
            log.warn("JWT authentication failed: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Authenticates one access token after checking the account's current
     * status and role. The role claim is required as a marker for an access
     * token because refresh tokens intentionally do not contain that claim.
     * The claim itself is never used for authorization; the role from the
     * database is stored in the security context instead.
     */
    private void authenticateAccessToken(String token) {
        String subject = jwtUtil.extractUsername(token);
        if (!StringUtils.hasText(subject)) {
            log.warn("JWT token has no subject");
            return;
        }

        if (!jwtUtil.validateToken(token, subject)) {
            log.warn("JWT token validation failed, subject={}", subject);
            return;
        }

        Long accountId = jwtUtil.extractAccountId(token);
        String tokenRole = jwtUtil.extractRole(token);

        // A refresh token has no role claim and cannot be used as an access token.
        if (!StringUtils.hasText(tokenRole)) {
            log.warn("JWT token has no access-token role claim, subject={}", subject);
            return;
        }

        if (accountId == null || !subject.equals(accountId.toString())) {
            log.warn("JWT subject/accountId mismatch, subject={}, accountId={}", subject, accountId);
            return;
        }

        Account account = accountMapper.selectById(accountId);
        if (account == null || !Integer.valueOf(1).equals(account.getStatus())) {
            log.warn("JWT account is missing or disabled, accountId={}", accountId);
            return;
        }

        String currentRole = account.getRole();
        if (!StringUtils.hasText(currentRole)) {
            log.warn("JWT account has no role, accountId={}", accountId);
            return;
        }

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(subject, null, Collections.emptyList());

        Map<String, Object> details = new HashMap<>();
        details.put("accountId", accountId);
        details.put("role", currentRole);
        authToken.setDetails(details);

        SecurityContextHolder.getContext().setAuthentication(authToken);
        log.info("JWT authentication succeeded, accountId={}, role={}", accountId, currentRole);
    }

    private String getTokenFromRequest(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");

        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return null;
    }
}
