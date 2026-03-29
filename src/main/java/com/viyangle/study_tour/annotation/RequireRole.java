package com.viyangle.study_tour.annotation;

import java.lang.annotation.*;

/**
 * 角色权限注解
 * 用于标记需要特定角色才能访问的接口
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {
    /**
     * 需要的角色类型
     */
    String[] value();
}
