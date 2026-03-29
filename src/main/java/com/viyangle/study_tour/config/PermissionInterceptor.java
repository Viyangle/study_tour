package com.viyangle.study_tour.config;

import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;

@Slf4j
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    // 构造器注入
    public PermissionInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, 
                           HttpServletResponse response, 
                           Object handler) throws Exception {
        
        // 只拦截 Controller 方法
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        Method method = handlerMethod.getMethod();

        // 检查是否有 @RequireRole 注解
        RequireRole requireRole = method.getAnnotation(RequireRole.class);
        if (requireRole == null) {
            // 没有注解，放行
            return true;
        }

        // 从请求头中获取 token 并提取角色
        String authHeader = request.getHeader("Authorization");
        String currentRole = null;
        String userId = "anonymous";
        
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                userId = jwtUtil.extractUsername(token);
                currentRole = jwtUtil.extractRole(token);
            } catch (Exception e) {
                log.warn("Token 解析失败：{}", e.getMessage());
            }
        } else {
            log.warn("未提供 Authorization header");
        }

        // 验证角色权限
        String[] requiredRoles = requireRole.value();
        boolean hasPermission = false;
        if (currentRole != null) {
            for (String required : requiredRoles) {
                if (required.equals(currentRole)) {
                    hasPermission = true;
                    break;
                }
            }
        }

        if (!hasPermission) {
            log.warn("权限验证失败：userId={}, 当前角色={}, 需要角色={}", 
                    userId, currentRole, String.join(", ", requiredRoles));
            
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                "{\"code\": 0, \"msg\": \"权限不足：需要 [" + String.join(", ", requiredRoles) + "] 角色\", \"data\": null}"
            );
            return false;
        }

        log.info("权限验证通过：role={}", currentRole);
        return true;
    }
}
