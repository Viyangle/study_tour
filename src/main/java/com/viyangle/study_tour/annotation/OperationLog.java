package com.viyangle.study_tour.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解
 * 用于记录用户的操作行为
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {
    /**
     * 操作描述
     */
    String value() default "";
    
    /**
     * 操作类型
     */
    String type() default "OTHER";
}
