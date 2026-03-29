package com.viyangle.study_tour.config;

import com.viyangle.study_tour.utils.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

/**
 * JWT认证过滤器
 * 每次请求都会经过这个过滤器
 * 作用：从请求头中取出token，验证后将用户信息注入Spring Security上下文
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // 从请求头中获取Authorization字段
        String authHeader = request.getHeader("Authorization");

        String token = null;
        String userId = null;

        // token格式应为 "Bearer <token>"
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7); // 去掉 "Bearer " 前缀
            try {
                userId = jwtUtil.extractUsername(token); // 提取用户ID
            } catch (Exception e) {
                // token解析失败（过期或格式错误），直接放行，后续会被Security拦截
            }
        }

        // 如果成功提取到userId，且当前Security上下文中还没有认证信息
        if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            // 构造认证对象，注入Spring Security上下文
            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(userId, null, new ArrayList<>());
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        // 继续执行后续过滤器
        filterChain.doFilter(request, response);
        System.out.println("JwtAuthFilter: authHeader=" + authHeader + ", userId=" + userId);
    }
}
