package com.viyangle.study_tour.utils;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * 离线评估：用历史路线的站点作为 relevance，比较「精确标签匹配」与「15 维向量」的排序质量。
 *
 * <p>运行前需要本地 MySQL 已执行 V4，并有 routes / route_attractions / attraction_tag 数据。</p>
 *
 * <pre>
 * mvn -DskipTests exec:java -Dexec.mainClass="com.viyangle.study_tour.utils.TagVectorEvalTool"
 * </pre>
 *
 * <p>输出：控制台汇总 + {@code eval_out/tag-vector-eval-时间戳.csv}。</p>
 */
public final class TagVectorEvalTool {

    private static final int TOP_K = 10;

    private static final String DB_URL = env("DB_URL",
            "jdbc:mysql://localhost:3306/study_tour?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
    private static final String DB_USER = env("DB_USER", env("DB_USERNAME", "root"));
    private static final String DB_PASS = env("DB_PASS", env("DB_PASSWORD", ""));

    private TagVectorEvalTool() {
    }

    public static void main(String[] args) throws Exception {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            EvalResult result = evaluate(connection, TOP_K);
            print(result);
            Path output = writeCsv(result);
            System.out.println("CSV: " + output.toAbsolutePath());
        }
        // MySQL Connector/J 的清理线程可能阻止 exec:java 退出，CLI 工具显式结束
        System.exit(0);
    }

    static EvalResult evaluate(Connection connection, int k) throws Exception {
        Map<String, Integer> ordinalByName = loadDimensions(connection);
        if (ordinalByName.isEmpty()) {
            throw new IllegalStateException("tag_dimension 为空，请先执行 V4 迁移");
        }
        int dimension = ordinalByName.size();

        Map<String, double[]> vectors = new HashMap<>();
        Map<String, Set<String>> tagNames = new HashMap<>();
        loadAttractionTags(connection, ordinalByName, dimension, vectors, tagNames);

        List<String> candidates = loadActivePoiIds(connection);
        Map<Long, String> routeTags = loadRouteTags(connection);
        Map<Long, List<String>> routeStops = loadRouteStops(connection);

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Long, String> entry : routeTags.entrySet()) {
            Long routeId = entry.getKey();
            String tag = entry.getValue();
            Integer ordinal = ordinalByName.get(tag);
            List<String> stops = routeStops.getOrDefault(routeId, List.of());
            if (ordinal == null || stops.isEmpty()) {
                continue;
            }

            Set<String> relevant = new LinkedHashSet<>(stops);
            double[] intent = new double[dimension];
            intent[ordinal] = 1.0;

            List<String> baseline = rank(candidates,
                    poiId -> tagNames.getOrDefault(poiId, Set.of()).contains(tag) ? 1.0 : 0.0);
            List<String> vector = rank(candidates, poiId -> dot(intent, vectors.get(poiId)));

            rows.add(new Row(
                    routeId,
                    tag,
                    relevant.size(),
                    TagVectorMetrics.recallAtK(baseline, relevant, k),
                    TagVectorMetrics.recallAtK(vector, relevant, k),
                    TagVectorMetrics.ndcgAtK(baseline, relevant, k),
                    TagVectorMetrics.ndcgAtK(vector, relevant, k)));
        }

        return new EvalResult(k, rows,
                average(rows, Row::baselineRecall), average(rows, Row::vectorRecall),
                average(rows, Row::baselineNdcg), average(rows, Row::vectorNdcg));
    }

    private static Map<String, Integer> loadDimensions(Connection connection) throws Exception {
        String sql = "select d.ordinal, t.name from tag_dimension d "
                + "join tags t on t.id = d.tag_id "
                + "where d.dim_version = (select max(dim_version) from tag_dimension) and d.enabled = 1 "
                + "order by d.ordinal";
        Map<String, Integer> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString("name"), rs.getInt("ordinal") - 1);
            }
        }
        return result;
    }

    private static void loadAttractionTags(Connection connection,
                                           Map<String, Integer> ordinalByName,
                                           int dimension,
                                           Map<String, double[]> vectors,
                                           Map<String, Set<String>> tagNames) throws Exception {
        String sql = "select at.poi_id, t.name, at.effective_weight "
                + "from attraction_tag at join tags t on t.id = at.tag_id";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                Integer ordinal = ordinalByName.get(rs.getString("name"));
                if (ordinal == null) {
                    continue;
                }
                String poiId = rs.getString("poi_id");
                double weight = Math.max(0.0, Math.min(1.0, rs.getDouble("effective_weight")));
                vectors.computeIfAbsent(poiId, key -> new double[dimension])[ordinal] = weight;
                tagNames.computeIfAbsent(poiId, key -> new LinkedHashSet<>()).add(rs.getString("name"));
            }
        }
    }

    private static List<String> loadActivePoiIds(Connection connection) throws Exception {
        String sql = "select poi_id from attractions where status is null or status = 'ACTIVE' order by poi_id";
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                result.add(rs.getString("poi_id"));
            }
        }
        return result;
    }

    private static Map<Long, String> loadRouteTags(Connection connection) throws Exception {
        String sql = "select id, tag from routes where tag is not null and tag <> ''";
        Map<Long, String> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getLong("id"), rs.getString("tag"));
            }
        }
        return result;
    }

    private static Map<Long, List<String>> loadRouteStops(Connection connection) throws Exception {
        String sql = "select route_id, poi_id from route_attractions order by route_id, visit_order";
        Map<Long, List<String>> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                result.computeIfAbsent(rs.getLong("route_id"), key -> new ArrayList<>())
                        .add(rs.getString("poi_id"));
            }
        }
        return result;
    }

    private static List<String> rank(List<String> candidates, ToDoubleFunction<String> scorer) {
        List<String> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingDouble(scorer::applyAsDouble).reversed());
        return ranked;
    }

    private static double dot(double[] intent, double[] vector) {
        if (intent == null || vector == null) {
            return 0.0;
        }
        int size = Math.min(intent.length, vector.length);
        double score = 0.0;
        for (int i = 0; i < size; i++) {
            score += intent[i] * vector[i];
        }
        return score;
    }

    private static double average(List<Row> rows, ToDoubleFunction<Row> getter) {
        return rows.isEmpty() ? 0.0 : rows.stream().mapToDouble(getter).average().orElse(0.0);
    }

    private static void print(EvalResult result) {
        System.out.printf("评估路线数: %d, K=%d%n", result.rows().size(), result.k());
        System.out.printf("精确标签  Recall@K=%.4f  NDCG@K=%.4f%n", result.baselineRecall(), result.baselineNdcg());
        System.out.printf("15维向量  Recall@K=%.4f  NDCG@K=%.4f%n", result.vectorRecall(), result.vectorNdcg());
        System.out.printf("提升      Recall %+.4f  NDCG %+.4f%n",
                result.vectorRecall() - result.baselineRecall(),
                result.vectorNdcg() - result.baselineNdcg());
    }

    private static Path writeCsv(EvalResult result) throws Exception {
        Files.createDirectories(Path.of("eval_out"));
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path output = Path.of("eval_out", "tag-vector-eval-" + timestamp + ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("routeId,tag,stopCount,baselineRecall,vectorRecall,baselineNdcg,vectorNdcg");
            writer.newLine();
            for (Row row : result.rows()) {
                writer.write(String.format("%d,%s,%d,%.4f,%.4f,%.4f,%.4f",
                        row.routeId(), row.tag(), row.stopCount(),
                        row.baselineRecall(), row.vectorRecall(),
                        row.baselineNdcg(), row.vectorNdcg()));
                writer.newLine();
            }
        }
        return output;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    record Row(long routeId,
               String tag,
               int stopCount,
               double baselineRecall,
               double vectorRecall,
               double baselineNdcg,
               double vectorNdcg) {
    }

    record EvalResult(int k,
                      List<Row> rows,
                      double baselineRecall,
                      double vectorRecall,
                      double baselineNdcg,
                      double vectorNdcg) {
    }
}
