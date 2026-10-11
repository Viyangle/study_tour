package com.viyangle.study_tour.interceptor;

import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;

@Slf4j
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        Method method = handlerMethod.getMethod();
        RequireRole requireRole = AnnotatedElementUtils.findMergedAnnotation(method, RequireRole.class);
        if (requireRole == null) {
            requireRole = AnnotatedElementUtils.findMergedAnnotation(
                    handlerMethod.getBeanType(), RequireRole.class);
        }
        if (requireRole == null) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            log.warn("Authentication required, uri={}", request.getRequestURI());
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或token无效");
            return false;
        }

        String currentRole = SecurityContextUtil.currentRole();
        String userId = authentication.getName();
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
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                    "权限不足，需要[" + String.join(", ", requiredRoles) + "]角色");
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

    private void writeJsonError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":0,\"msg\":\"" + escapeJson(message) + "\",\"data\":null}"
        );
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
