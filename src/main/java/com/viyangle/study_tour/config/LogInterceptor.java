package com.viyangle.study_tour.config;

import com.viyangle.study_tour.annotation.OperationLog;
import com.viyangle.study_tour.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.lang.reflect.Method;

@Slf4j
@Component
public class LogInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    // 构造器注入
    public LogInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    private static final ThreadLocal<Long> startTimeThreadLocal = new ThreadLocal<>();

    @Override
    public boolean preHandle(HttpServletRequest request,
                           HttpServletResponse response, 
                           Object handler) {
        
        // 记录请求开始时间
        startTimeThreadLocal.set(System.currentTimeMillis());

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        Method method = handlerMethod.getMethod();

        // 检查是否有 @OperationLog 注解
        OperationLog operationLog = method.getAnnotation(OperationLog.class);
        if (operationLog != null) {
            // 尝试从 token 中提取用户 ID
            String userId = "anonymous";
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                try {
                    userId = jwtUtil.extractUsername(token);
                } catch (Exception e) {
                    // token 无效或过期，使用 anonymous
                }
            }
            
            String uri = request.getRequestURI();
            String methodType = request.getMethod();
            
            log.info("=== 操作日志 START ===");
            log.info("用户 ID: {}", userId);
            log.info("操作：{}", operationLog.value());
            log.info("类型：{}", operationLog.type());
            log.info("请求：{} {}", methodType, uri);
            log.info("参数：{}", request.getQueryString());
            log.info("=== 操作日志 END (START) ===");
        }

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, 
                         HttpServletResponse response, 
                         Object handler,
                         ModelAndView modelAndView) {
        
        if (!(handler instanceof HandlerMethod)) {
            return;
        }

        Method method = ((HandlerMethod) handler).getMethod();
        OperationLog operationLog = method.getAnnotation(OperationLog.class);
        
        if (operationLog != null) {
            Long startTime = startTimeThreadLocal.get();
            if (startTime != null) {
                long costTime = System.currentTimeMillis() - startTime;
                log.info("耗时：{} ms", costTime);
            }
            log.info("状态码：{}", response.getStatus());
            log.info("=== 操作日志 END ===");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, 
                              HttpServletResponse response, 
                              Object handler,
                              Exception ex) {
        startTimeThreadLocal.remove();
        
        if (ex != null) {
            log.error("请求处理异常：", ex);
        }
    }
}
