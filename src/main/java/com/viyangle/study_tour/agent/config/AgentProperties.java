package com.viyangle.study_tour.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Agent 运行限制，对应文档第 8 节“运行限制与部署验收”。
 *
 * <p>任务存储是进程内存（单 worker），重启会丢失任务，与文档描述一致。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {

    /** 单轮整体执行时间上限。 */
    private Duration executionTimeout = Duration.ofSeconds(240);

    /** 任务从创建时刻起保留多久（终态任务按请求触发清理，运行中任务不清理）。 */
    private Duration runTtl = Duration.ofMinutes(30);

    /** 保存任务上限。 */
    private int maxStoredRuns = 500;

    /** 全局活跃任务上限。 */
    private int maxActiveRuns = 8;

    /** 新建频率窗口。 */
    private Duration createWindow = Duration.ofSeconds(60);

    /** 单个账号在一个窗口内最多新建次数；幂等重复不占额度。 */
    private int maxCreatesPerWindow = 6;

    /** 每次查询最多返回的事件条数。 */
    private int maxEvents = 110;

    /** 后台执行线程数。 */
    private int workerThreads = 4;

    /** 单轮最多发起的路径规划请求数（不计入既有规划服务内部的检索请求）。 */
    private int maxDirectionRequests = 90;

    /** 终态任务清理的检查间隔。 */
    private Duration sweepInterval = Duration.ofMinutes(1);
}
