package com.viyangle.study_tour.interceptor;

import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;

@Slf4j
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    public PermissionInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        Method method = handlerMethod.getMethod();

        RequireRole requireRole = method.getAnnotation(RequireRole.class);
        if (requireRole == null) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        String currentRole = null;
        String userId = "anonymous";

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                userId = jwtUtil.extractUsername(token);
                currentRole = jwtUtil.extractRole(token);
            } catch (Exception e) {
                log.warn("Token parse failed: {}", e.getMessage());
            }
        } else {
            log.warn("Authorization header missing");
        }

        String[] requiredRoles = requireRole.value();
        boolean hasPermission = false;
        if (currentRole != null) {
            for (String required : requiredRoles) {
                if (required.equalsIgnoreCase(currentRole) || hasBothPermission(currentRole, required)) {
                    hasPermission = true;
                    break;
                }
            }
        }

        if (!hasPermission) {
            log.warn("Permission denied, userId={}, currentRole={}, requiredRoles={}",
                userId, currentRole, String.join(", ", requiredRoles));

            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                "{\"code\": 0, \"msg\": \"权限不足，需要[" + String.join(", ", requiredRoles) + "]角色\", \"data\": null}"
            );
            return false;
        }

        log.info("Permission granted, role={}", currentRole);
        return true;
    }

    private boolean hasBothPermission(String currentRole, String requiredRole) {
        if (!"BOTH".equalsIgnoreCase(currentRole)) {
            return false;
        }
        return "USER".equalsIgnoreCase(requiredRole) || "LEADER".equalsIgnoreCase(requiredRole);
    }
}