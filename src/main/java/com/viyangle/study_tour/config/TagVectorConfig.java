package com.viyangle.study_tour.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 反馈事件处理器需要定时轮询；当前项目是单实例，直接用 Spring 的默认调度线程即可。
 */
@Configuration
@EnableScheduling
public class TagVectorConfig {
}
