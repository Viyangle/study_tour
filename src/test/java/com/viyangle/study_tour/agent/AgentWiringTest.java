package com.viyangle.study_tour.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.agent.config.AgentProperties;
import com.viyangle.study_tour.agent.controller.AgentRunController;
import com.viyangle.study_tour.agent.controller.HealthController;
import com.viyangle.study_tour.agent.service.AgentPlanAssembler;
import com.viyangle.study_tour.agent.service.AgentRunExecutor;
import com.viyangle.study_tour.agent.service.AgentRunStore;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.service.AiRoutePlanningService;
import com.viyangle.study_tour.utils.AmapDirectionClient;
import com.viyangle.study_tour.utils.AmapTransitClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 装配冒烟测试：Agent 包在 Spring 上下文里能启动，且 {@code app.agent.*} 能绑定到
 * {@link AgentProperties}（真实应用启动需要 MySQL/Redis/模型，这里只验证装配与配置绑定）。
 */
class AgentWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    JacksonAutoConfiguration.class))
            .withUserConfiguration(AgentScanConfig.class)
            .withPropertyValues(
                    "app.agent.execution-timeout=90s",
                    "app.agent.run-ttl=15m",
                    "app.agent.max-stored-runs=42",
                    "app.agent.max-active-runs=3",
                    "app.agent.create-window=30s",
                    "app.agent.max-creates-per-window=2",
                    "app.agent.max-events=20",
                    "app.agent.worker-threads=2",
                    "app.agent.max-direction-requests=7",
                    "app.agent.sweep-interval=5s");

    @Test
    void contextStartsWithAgentBeansAndBindsProperties() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentRunController.class);
            assertThat(context).hasSingleBean(HealthController.class);
            assertThat(context).hasSingleBean(AgentRunStore.class);
            assertThat(context).hasSingleBean(AgentRunExecutor.class);
            assertThat(context).hasSingleBean(AgentPlanAssembler.class);

            AgentProperties props = context.getBean(AgentProperties.class);
            assertThat(props.getExecutionTimeout()).isEqualTo(Duration.ofSeconds(90));
            assertThat(props.getRunTtl()).isEqualTo(Duration.ofMinutes(15));
            assertThat(props.getMaxStoredRuns()).isEqualTo(42);
            assertThat(props.getMaxActiveRuns()).isEqualTo(3);
            assertThat(props.getCreateWindow()).isEqualTo(Duration.ofSeconds(30));
            assertThat(props.getMaxCreatesPerWindow()).isEqualTo(2);
            assertThat(props.getMaxEvents()).isEqualTo(20);
            assertThat(props.getWorkerThreads()).isEqualTo(2);
            assertThat(props.getMaxDirectionRequests()).isEqualTo(7);
            assertThat(props.getSweepInterval()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void defaultsMatchDocumentedRunLimits() {
        AgentProperties props = new AgentProperties();
        assertThat(props.getExecutionTimeout()).isEqualTo(Duration.ofSeconds(240));
        assertThat(props.getRunTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.getMaxStoredRuns()).isEqualTo(500);
        assertThat(props.getMaxActiveRuns()).isEqualTo(8);
        assertThat(props.getCreateWindow()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.getMaxCreatesPerWindow()).isEqualTo(6);
        assertThat(props.getMaxEvents()).isEqualTo(110);
        assertThat(props.getMaxDirectionRequests()).isEqualTo(90);
    }

    @Configuration
    @ComponentScan(basePackages = "com.viyangle.study_tour.agent")
    static class AgentScanConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AttractionMapper attractionMapper() {
            return mock(AttractionMapper.class);
        }

        @Bean
        AmapTransitClient amapTransitClient() {
            return mock(AmapTransitClient.class);
        }

        /**
         * 装配上下文不启动 Redis，AmapTransitClient 的 @Autowired 字段仍会被容器填充，
         * 因此这里补一个 mock bean（真实部署由 spring-boot-starter-data-redis 提供）。
         */
        @Bean
        org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate() {
            return mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        }

        @Bean
        AmapDirectionClient amapDirectionClient() {
            return mock(AmapDirectionClient.class);
        }

        @Bean
        AiRoutePlanningService aiRoutePlanningService() {
            return mock(AiRoutePlanningService.class);
        }
    }
}
