package com.viyangle.study_tour.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InterceptorConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;
    private final LogInterceptor logInterceptor;

    // 构造器注入
    public InterceptorConfig(PermissionInterceptor permissionInterceptor, LogInterceptor logInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
        this.logInterceptor = logInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 权限验证拦截器
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/projects/**", "/routes/**", "/reviews/**", "/accounts/**")
                .excludePathPatterns("/login/**", "/register/**", "/login/ping");

        // 2. 操作日志拦截器（所有请求都记录）
        registry.addInterceptor(logInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/login/**", "/register/**", "/login/ping");
    }
}
