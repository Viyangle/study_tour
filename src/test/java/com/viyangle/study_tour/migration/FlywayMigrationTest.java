package com.viyangle.study_tour.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway 迁移的集成测试，需要本机 MySQL。
 *
 * <p>验证两条路径：空库从 V1 建表并执行到 V2；已有结构的库通过
 * baseline-on-migrate 跳过 V1，只执行 V2。CI 通过 {@code -Pci} 排除本类。</p>
 */
@Tag("requires-infra")
class FlywayMigrationTest {

    private static final String HOST = env("DB_HOST", "localhost");
    private static final String PORT = env("DB_PORT", "3306");
    private static final String USER = env("DB_USERNAME", "root");
    private static final String PASSWORD = env("DB_PASSWORD", "123456");
    private static final String SCHEMA = "study_tour_flyway_it";

    private static final String OPTIONS =
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String serverUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + OPTIONS;
    }

    private static String schemaUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + SCHEMA + OPTIONS;
    }

    private static void recreateSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(serverUrl(), USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            statement.execute("CREATE DATABASE " + SCHEMA
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    private static void dropSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(serverUrl(), USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }

    @Test
    @DisplayName("空库：执行 V1 建表与 V2 增量，角色枚举包含 ADMIN")
    void migratesEmptySchemaToLatest() throws Exception {
        recreateSchema();
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(schemaUrl(), USER, PASSWORD)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .load();

            MigrateResult result = flyway.migrate();

            assertEquals(2, result.migrationsExecuted, "空库应执行 V1 与 V2");
            assertEquals(20, countTables(), "基线应创建 20 张业务表");
            assertTrue(roleColumnType().contains("ADMIN"), roleColumnType());
        } finally {
            dropSchema();
        }
    }

    @Test
    @DisplayName("存量库：baseline-on-migrate 跳过 V1，仅执行 V2")
    void baselinesExistingSchemaAndAppliesIncrement() throws Exception {
        recreateSchema();
        try {
            // 模拟线上已有结构：只有 accounts 表，role 仍是旧枚举
            try (Connection connection = DriverManager.getConnection(schemaUrl(), USER, PASSWORD);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE accounts ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "role ENUM('USER','LEADER','BOTH') NOT NULL DEFAULT 'USER',"
                        + "PRIMARY KEY (id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }

            Flyway flyway = Flyway.configure()
                    .dataSource(schemaUrl(), USER, PASSWORD)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .load();

            MigrateResult result = flyway.migrate();

            assertEquals(1, result.migrationsExecuted, "存量库只应执行 V2");
            assertTrue(roleColumnType().contains("ADMIN"), roleColumnType());
        } finally {
            dropSchema();
        }
    }

    private static int countTables() throws Exception {
        try (Connection connection = DriverManager.getConnection(schemaUrl(), USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '" + SCHEMA + "'"
                             + " AND TABLE_NAME <> 'flyway_schema_history'")) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static String roleColumnType() throws Exception {
        try (Connection connection = DriverManager.getConnection(schemaUrl(), USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COLUMN_TYPE FROM information_schema.COLUMNS"
                             + " WHERE TABLE_SCHEMA = '" + SCHEMA + "'"
                             + " AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'role'")) {
            assertTrue(resultSet.next(), "accounts.role 应存在");
            return resultSet.getString(1);
        }
    }
}
