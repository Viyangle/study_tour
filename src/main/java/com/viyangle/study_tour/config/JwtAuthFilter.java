package com.viyangle.study_tour.config;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 认证过滤器
 * 每次请求都会经过这个过滤器
 * 作用：从请求头中取出 token，验证后将用户信息注入 Spring Security 上下文
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String token = getTokenFromRequest(request);

            if (StringUtils.hasText(token)) {
                String userId = jwtUtil.extractUsername(token);
                
                // 验证 token 是否有效
                if (StringUtils.hasText(userId) && 
                    SecurityContextHolder.getContext().getAuthentication() == null) {
                    
                    if (jwtUtil.validateToken(token, userId)) {
                        // 从 token 中提取角色信息
                        String role = jwtUtil.extractRole(token);
                        Long accountId = jwtUtil.extractAccountId(token);
                        
                        // 构造认证对象，注入 Spring Security 上下文
                        UsernamePasswordAuthenticationToken authToken =
                                new UsernamePasswordAuthenticationToken(
                                        userId, 
                                        null, 
                                        new ArrayList<>() // 后续可以从数据库加载权限
                                );
                        
                        // 设置详细信息（包含 accountId 和 role）
                        Map<String, Object> details = new HashMap<>();
                        details.put("accountId", accountId);
                        details.put("role", role);
                        authToken.setDetails(details);
                        
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                        log.info("JWT 认证成功：userId={}, role={}", userId, role);
                    } else {
                        log.warn("JWT token 验证失败：token 已过期或无效，userId={}", userId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("JWT 认证处理失败：{}", e.getMessage());
        }

        // 继续执行后续过滤器
        filterChain.doFilter(request, response);
    }

    /**
     * 从请求头中获取 token
     */
    private String getTokenFromRequest(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        
        return null;
    }
}