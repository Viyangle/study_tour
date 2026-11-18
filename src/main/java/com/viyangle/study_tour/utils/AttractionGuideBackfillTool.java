package com.viyangle.study_tour.utils;

import com.viyangle.study_tour.mapper.AttractionGuideMapper;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.service.AttractionGuideSourceClient;
import com.viyangle.study_tour.service.impl.AttractionGuideContentGenerator;
import com.viyangle.study_tour.service.impl.AttractionGuideSyncServiceImpl;
import com.viyangle.study_tour.service.impl.BaiduBaikeAttractionGuideSourceClient;
import com.viyangle.study_tour.service.impl.BaiduBaikeMobileAttractionGuideSourceClient;
import com.viyangle.study_tour.service.impl.QiuwenAttractionGuideSourceClient;
import com.viyangle.study_tour.service.impl.WikipediaAttractionGuideSourceClient;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 独立回填景点讲解，供首次导入和批量景点脚本调用；不依赖 Spring 启动。
 *
 * <pre>
 * mvn -DskipTests exec:java -Dexec.mainClass="com.viyangle.study_tour.utils.AttractionGuideBackfillTool"
 * </pre>
 */
public class AttractionGuideBackfillTool {

    private static final String QIUWEN_ENDPOINT = "https://www.qiuwenbaike.cn/api.php";
    private static final String WIKIPEDIA_ENDPOINT = "https://zh.wikipedia.org/w/api.php";
    private static final String BAIDU_ENDPOINT = "https://baike.baidu.com/api/openapi/BaikeLemmaCardApi";
    private static final String BAIDU_MOBILE_ENDPOINT = "https://wapbaike.baidu.com/item/";

    public static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        int synced = run(config.dbUrl, config.dbUser, config.dbPassword,
                config.limit, config.includeInactive, config.poiId);
        System.out.printf("Done: guidesSynced=%d, includeInactive=%s, poiId=%s%n",
                synced, config.includeInactive, config.poiId.isBlank() ? "(all)" : config.poiId);
    }

    public static int run(String dbUrl, String dbUser, String dbPassword, int limit, boolean includeInactive)
            throws Exception {
        return run(dbUrl, dbUser, dbPassword, limit, includeInactive, null);
    }

    public static int run(String dbUrl, String dbUser, String dbPassword, int limit,
                          boolean includeInactive, String filterPoiId) throws Exception {
        migrate(dbUrl, dbUser, dbPassword);
        UnpooledDataSource dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", dbUrl, dbUser, dbPassword);
        Environment environment = new Environment("guide-backfill", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        parseMapper(configuration, "com/viyangle/study_tour/mapper/AttractionMapper.xml");
        parseMapper(configuration, "com/viyangle/study_tour/mapper/AttractionGuideMapper.xml");

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true)) {
            AttractionMapper attractionMapper = session.getMapper(AttractionMapper.class);
            AttractionGuideMapper guideMapper = session.getMapper(AttractionGuideMapper.class);

            AttractionGuideContentGenerator generator = createGenerator();
            List<AttractionGuideSourceClient> sourceClients = createSourceClients();
            AttractionGuideSyncServiceImpl syncService = new AttractionGuideSyncServiceImpl(
                    guideMapper, attractionMapper, sourceClients, generator);

            List<String> poiIds = filterPoiId == null || filterPoiId.isBlank()
                    ? loadPoiIds(session.getConnection(), includeInactive, limit)
                    : List.of(filterPoiId.trim());
            int synced = 0;
            int failed = 0;
            for (String poiId : poiIds) {
                try {
                    Attraction attraction = attractionMapper.selectByPoiId(poiId);
                    if (attraction != null && syncService.syncGuide(attraction)) {
                        synced++;
                    }
                } catch (Exception e) {
                    failed++;
                    System.err.printf("Guide sync failed, poiId=%s: %s%n", poiId, e.getMessage());
                }
            }
            printStatusSummary(session.getConnection());
            System.out.printf("Guide backfill: candidates=%d, synced=%d, failed=%d, ai=%s%n",
                    poiIds.size(), synced, failed, generator.usingAi());
            return synced;
        } finally {
            shutdownMysqlCleanupThread();
        }
    }

    private static void migrate(String dbUrl, String dbUser, String dbPassword) {
        Flyway.configure()
                .dataSource(dbUrl, dbUser, dbPassword)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()
                .migrate();
    }

    private static void parseMapper(Configuration configuration, String resource) throws Exception {
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private static List<String> loadPoiIds(Connection connection, boolean includeInactive, int limit) throws Exception {
        int max = limit <= 0 ? 500 : Math.min(limit, 500);
        String sql = "SELECT poi_id FROM attractions "
                + (includeInactive ? "" : "WHERE (status IS NULL OR status = 'ACTIVE') ")
                + "ORDER BY (status IS NULL OR status = 'ACTIVE') DESC, updated_at DESC LIMIT " + max;
        List<String> poiIds = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                poiIds.add(resultSet.getString(1));
            }
        }
        return poiIds;
    }

    private static AttractionGuideContentGenerator createGenerator() {
        String apiKey = env("CHAT_MODEL_KEY", "");
        String baseUrl = env("CHAT_MODEL_BASE_URL", "https://api.deepseek.com");
        String model = env("CHAT_MODEL_NAME", "deepseek-flash");
        boolean aiEnabled = Boolean.parseBoolean(env("GUIDE_AI_ENABLED", "true"));
        return new AttractionGuideContentGenerator(aiEnabled, apiKey, baseUrl, model, 45);
    }

    private static List<AttractionGuideSourceClient> createSourceClients() {
        String baiduAppId = env("BAIDU_BAIKE_APP_ID", "");
        boolean baiduEnabled = Boolean.parseBoolean(env("BAIDU_BAIKE_ENABLED", "false"))
                && !baiduAppId.isBlank();
        boolean baiduMobileEnabled = Boolean.parseBoolean(env("BAIDU_BAIKE_MOBILE_ENABLED", "true"));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(12)).build();
        return List.of(
                new QiuwenAttractionGuideSourceClient(QIUWEN_ENDPOINT, 12, 150),
                new WikipediaAttractionGuideSourceClient(false, WIKIPEDIA_ENDPOINT, 12, 150, httpClient),
                new BaiduBaikeMobileAttractionGuideSourceClient(
                        baiduMobileEnabled, BAIDU_MOBILE_ENDPOINT, 12, httpClient),
                new BaiduBaikeAttractionGuideSourceClient(
                        baiduEnabled, baiduAppId, BAIDU_ENDPOINT, 12));
    }

    private static void printStatusSummary(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT sync_status, source_type, COUNT(*) FROM attraction_guides "
                             + "GROUP BY sync_status, source_type ORDER BY sync_status, source_type")) {
            System.out.println("Guide source summary:");
            while (resultSet.next()) {
                System.out.printf("  status=%s, source=%s, count=%d%n",
                        resultSet.getString(1), resultSet.getString(2), resultSet.getInt(3));
            }
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void shutdownMysqlCleanupThread() {
        try {
            Class<?> cleanup = Class.forName("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread");
            cleanup.getMethod("checkedShutdown").invoke(null);
            cleanup.getMethod("uncheckedShutdown").invoke(null);
        } catch (Exception ignored) {
            // best-effort cleanup for exec:java shutdown
        }
    }

    private static class Config {
        private String dbUrl = "jdbc:mysql://" + env("DB_HOST", "localhost") + ":"
                + env("DB_PORT", "3306") + "/" + env("DB_NAME", "study_tour")
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
                + "&characterEncoding=utf8";
        private String dbUser = env("DB_USERNAME", "root");
        private String dbPassword = env("DB_PASSWORD", "123456");
        private int limit = 500;
        private boolean includeInactive = true;
        private String poiId = "";

        static Config fromArgs(String[] args) {
            Config config = new Config();
            if (args == null) {
                return config;
            }
            for (String arg : args) {
                if (arg == null || !arg.startsWith("--")) {
                    continue;
                }
                int idx = arg.indexOf('=');
                if (idx <= 2) {
                    continue;
                }
                String key = arg.substring(2, idx);
                String value = arg.substring(idx + 1);
                switch (key) {
                    case "dbUrl" -> config.dbUrl = value;
                    case "dbUser" -> config.dbUser = value;
                    case "dbPassword" -> config.dbPassword = value;
                    case "limit" -> config.limit = Integer.parseInt(value);
                    case "includeInactive" -> config.includeInactive = Boolean.parseBoolean(value);
                    case "poiId" -> config.poiId = value;
                    default -> {
                        // ignore unknown option
                    }
                }
            }
            return config;
        }
    }
}
