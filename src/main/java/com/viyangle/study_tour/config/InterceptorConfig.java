package com.viyangle.study_tour.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InterceptorConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;
    private final LogInterceptor logInterceptor;

    @Value("${app.security.enabled:true}")
    private boolean securityEnabled;

    public InterceptorConfig(PermissionInterceptor permissionInterceptor, LogInterceptor logInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
        this.logInterceptor = logInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (securityEnabled) {
            registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/projects/**", "/routes/**", "/reviews/**", "/accounts/**")
                .excludePathPatterns("/login/**", "/register/**", "/login/ping");
        }

        registry.addInterceptor(logInterceptor)
            .addPathPatterns("/**")
            .excludePathPatterns("/login/**", "/register/**", "/login/ping");
    }
}
