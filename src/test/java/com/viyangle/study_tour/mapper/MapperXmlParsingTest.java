package com.viyangle.study_tour.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不启动 Spring、也不连数据库，直接用 MyBatis 解析全部 mapper XML。
 *
 * <p>作用：捕获 XML 语法错误、namespace 与 mapper 接口不匹配、statement id 缺失或重复，
 * 以及动态 SQL 写错 —— 这类问题只在应用启动时才暴露。另外单独验证
 * {@code ProjectMapper.selectByAccountRelation} 的 {@code status in (...)} 动态条件，
 * 这是「我的项目」阶段筛选改成多状态后新增的写法。</p>
 */
class MapperXmlParsingTest {

    private static final String MAPPER_LOCATION = "classpath*:com/viyangle/study_tour/mapper/*.xml";

    private Configuration parseAllMappers() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(MAPPER_LOCATION);
        assertThat(resources).as("mapper XML 资源").isNotEmpty();

        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Resource resource : resources) {
            try (InputStream inputStream = resource.getInputStream()) {
                new XMLMapperBuilder(inputStream,
                        configuration,
                        resource.getURL().toString(),
                        configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    @Test
    void everyMapperXmlParsesAndExpectedStatementsExist() throws Exception {
        Configuration configuration = parseAllMappers();

        assertThat(configuration.hasStatement(
                "com.viyangle.study_tour.mapper.ProjectMapper.selectByAccountRelation")).isTrue();
        assertThat(configuration.hasStatement(
                "com.viyangle.study_tour.mapper.ProjectMapper.casAbandonProject")).isTrue();
        assertThat(configuration.hasStatement(
                "com.viyangle.study_tour.mapper.ProjectAbandonRecordMapper.insert")).isTrue();
        assertThat(configuration.hasStatement(
                "com.viyangle.study_tour.mapper.ProjectAbandonRecordMapper.selectByProjectId")).isTrue();
        assertThat(configuration.hasStatement(
                "com.viyangle.study_tour.mapper.ChatSessionMapper.removeProjectLeader")).isTrue();
    }

    @Test
    void selectByAccountRelationFiltersByMultipleStatuses() throws Exception {
        Configuration configuration = parseAllMappers();
        String statementId = "com.viyangle.study_tour.mapper.ProjectMapper.selectByAccountRelation";

        // 同一个 relation 下比较：不带 statuses 与带两个 statuses 的差异必须正好是两个占位符。
        BoundSql withoutStatuses = configuration.getMappedStatement(statementId)
                .getBoundSql(parameters("ALL", null));
        BoundSql withStatuses = configuration.getMappedStatement(statementId)
                .getBoundSql(parameters("ALL", List.of("CONFIRMED", "IN_PROGRESS")));

        assertThat(withoutStatuses.getSql()).doesNotContain("p.status in");
        assertThat(withStatuses.getSql()).contains("p.status in");
        assertThat(countPlaceholders(withStatuses.getSql()))
                .isEqualTo(countPlaceholders(withoutStatuses.getSql()) + 2);
        assertThat(withStatuses.getParameterMappings())
                .hasSize(withoutStatuses.getParameterMappings().size() + 2);
    }

    @Test
    void selectByAccountRelationSkipsStatusFilterWhenStatusesEmpty() throws Exception {
        Configuration configuration = parseAllMappers();
        String statementId = "com.viyangle.study_tour.mapper.ProjectMapper.selectByAccountRelation";

        BoundSql emptyStatuses = configuration.getMappedStatement(statementId)
                .getBoundSql(parameters("ALL", List.of()));
        BoundSql withoutStatuses = configuration.getMappedStatement(statementId)
                .getBoundSql(parameters("ALL", null));

        assertThat(emptyStatuses.getSql()).doesNotContain("p.status in");
        assertThat(countPlaceholders(emptyStatuses.getSql()))
                .isEqualTo(countPlaceholders(withoutStatuses.getSql()));
    }

    private static Map<String, Object> parameters(String relation, List<String> statuses) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("accountId", 1L);
        parameters.put("relation", relation);
        parameters.put("statuses", statuses);
        return parameters;
    }

    private static int countPlaceholders(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }
}
