package com.viyangle.study_tour;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 应用上下文加载冒烟测试。
 *
 * 需要本机可用的 MySQL（localhost:3306）与 Redis（localhost:6379），
 * 因此标记为 {@code requires-infra}：CI 通过 {@code -Pci} 排除该分组，
 * 本地具备上述服务时用 {@code mvn test} 执行。
 */
@SpringBootTest
@Tag("requires-infra")
class StudyTourApplicationTests {

    @Test
    void contextLoads() {
    }

}
