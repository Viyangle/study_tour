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
 * <p>验证两条路径：空库从 V1 建表并执行到最新版本；已有结构的库通过
 * baseline-on-migrate 跳过 V1，只执行 V2 及之后的增量。两条路径都必须能拿到
 * {@code project_abandon_records}（V1 建表、V3 幂等补建），否则放弃带队会 500。
 * CI 通过 {@code -Pci} 排除本类。</p>
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
    @DisplayName("空库：执行 V1–V5，角色枚举、放弃记录、标签向量与路线向量表存在")
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

            assertEquals(5, result.migrationsExecuted, "空库应执行 V1–V5");
            assertEquals(24, countTables(), "基线 20 张业务表 + V4 的 3 张 + V5 的 1 张");
            assertTrue(roleColumnType().contains("ADMIN"), roleColumnType());
            assertTrue(tableExists("project_abandon_records"), "放弃带队审计表应存在");
            assertTrue(tableExists("tag_dimension"), "标签维度表应存在");
            assertTrue(tableExists("tag_feedback_event"), "反馈事件表应存在");
            assertTrue(tableExists("route_tag_weight"), "路线标签向量表应存在");
            assertTrue(hasColumn("attraction_tag", "effective_weight"),
                    "attraction_tag 应有生成列 effective_weight");
        } finally {
            dropSchema();
        }
    }

    @Test
    @DisplayName("存量库：baseline-on-migrate 跳过 V1，仅执行 V2–V5，并补出向量表")
    void baselinesExistingSchemaAndAppliesIncrement() throws Exception {
        recreateSchema();
        try {
            // 模拟线上已有结构：核心表已存在，accounts.role 仍是旧枚举，且没有放弃记录表/向量表
            try (Connection connection = DriverManager.getConnection(schemaUrl(), USER, PASSWORD);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE accounts ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "role ENUM('USER','LEADER','BOTH') NOT NULL DEFAULT 'USER',"
                        + "PRIMARY KEY (id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                statement.execute("CREATE TABLE projects ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "PRIMARY KEY (id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                statement.execute("CREATE TABLE routes ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "tag VARCHAR(64) DEFAULT NULL,"
                        + "PRIMARY KEY (id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                statement.execute("CREATE TABLE tags ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "name VARCHAR(50) NOT NULL,"
                        + "type ENUM('INTEREST','ROUTE_STYLE','CROWD','SCENIC') NOT NULL DEFAULT 'INTEREST',"
                        + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "PRIMARY KEY (id),"
                        + "UNIQUE KEY name (name)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                statement.execute("CREATE TABLE attraction_tag ("
                        + "poi_id VARCHAR(32) NOT NULL,"
                        + "tag_id BIGINT NOT NULL,"
                        + "source VARCHAR(16) NOT NULL DEFAULT 'LLM',"
                        + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "PRIMARY KEY (poi_id, tag_id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }

            Flyway flyway = Flyway.configure()
                    .dataSource(schemaUrl(), USER, PASSWORD)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .load();

            MigrateResult result = flyway.migrate();

            assertEquals(4, result.migrationsExecuted, "存量库只应执行 V2–V5");
            assertTrue(roleColumnType().contains("ADMIN"), roleColumnType());
            assertTrue(tableExists("project_abandon_records"),
                    "存量库跳过 V1 后，V3 必须补出放弃带队审计表");
            assertTrue(tableExists("tag_dimension"),
                    "存量库跳过 V1 后，V4 必须补出标签维度表");
            assertTrue(tableExists("route_tag_weight"),
                    "存量库跳过 V1 后，V5 必须补出路线标签向量表");
            assertTrue(hasColumn("attraction_tag", "effective_weight"),
                    "存量库的 attraction_tag 应补上 effective_weight");
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

    private static boolean tableExists(String tableName) throws Exception {
        try (Connection connection = DriverManager.getConnection(schemaUrl(), USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '" + SCHEMA + "'"
                             + " AND TABLE_NAME = '" + tableName + "'")) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1) == 1;
        }
    }

    private static boolean hasColumn(String tableName, String columnName) throws Exception {
        try (Connection connection = DriverManager.getConnection(schemaUrl(), USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '" + SCHEMA + "'"
                             + " AND TABLE_NAME = '" + tableName + "'"
                             + " AND COLUMN_NAME = '" + columnName + "'")) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1) == 1;
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
