package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线 pairwise 训练：从真实曝光 + 会话结算事件中构造 chosen vs shown-not-chosen，
 * 训练一个线性 pairwise 逻辑回归，并把系数写入 {@code tag_ranking_model}。
 *
 * <p>只使用在线 scorer 能算的特征：prior_match / learned_match / popularity /
 * session_match，避免训练和服务特征不一致。</p>
 *
 * <pre>
 * mvn -DskipTests exec:java -Dexec.mainClass="com.viyangle.study_tour.utils.TagPairwiseTrainer"
 * </pre>
 */
public final class TagPairwiseTrainer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[] FEATURES = {
            "prior_match", "learned_match", "popularity", "session_match"
    };
    private static final double LEARNED_PRIOR_PSEUDO_COUNT = 3.0;

    private static final String DB_URL = env("DB_URL",
            "jdbc:mysql://localhost:3306/study_tour?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
    private static final String DB_USER = env("DB_USER", env("DB_USERNAME", "root"));
    private static final String DB_PASS = env("DB_PASS", env("DB_PASSWORD", ""));

    private TagPairwiseTrainer() {
    }

    public static void main(String[] args) throws Exception {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            TrainResult result = train(connection);
            if (result.samples() == 0) {
                System.out.println("没有可训练样本：需要 tag_impression + ROUTE_STOP_CONFIRM/REJECT 事件。");
                return;
            }
            System.out.printf("sessions=%d pairs=%d train=%d test=%d pairwise_accuracy=%.4f priors=%.4f learned=%.4f blend=%.4f%n",
                    result.sessions(), result.samples(), result.trainSamples(), result.testSamples(),
                    result.accuracy(), result.priorAccuracy(), result.learnedAccuracy(), result.blendAccuracy());
            System.out.printf("modelVersion=%s%n", result.modelVersion());
            System.out.println("coefficients=" + result.coefficients());
            System.out.println("intercept=" + result.intercept());
        }
        System.exit(0);
    }

    static TrainResult train(Connection connection) throws Exception {
        Map<Long, Integer> ordinalByTagId = loadDimensions(connection);
        int dimension = ordinalByTagId.size();
        if (dimension == 0) {
            throw new IllegalStateException("tag_dimension 为空，请先执行 V4/V6 迁移");
        }

        Map<String, double[]> priors = loadPriors(connection, ordinalByTagId, dimension);
        Map<String, double[]> learned = loadLearned(connection, priors, ordinalByTagId, dimension);
        Map<String, Integer> popularityUsers = loadPopularity(connection);
        int maxPopularity = popularityUsers.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Map<String, SessionEvents> sessions = loadSessions(connection);

        List<Sample> all = new ArrayList<>();
        for (Map.Entry<String, SessionEvents> entry : sessions.entrySet()) {
            SessionEvents events = entry.getValue();
            if (events.intent == null || events.intent.length != dimension
                    || events.positives.isEmpty() || events.negatives.isEmpty()) {
                continue;
            }
            for (String positive : events.positives) {
                for (String negative : events.negatives) {
                    if (positive.equals(negative)) {
                        continue;
                    }
                    double[] diff = new double[FEATURES.length];
                    for (int i = 0; i < FEATURES.length; i++) {
                        diff[i] = feature(i, positive, negative, events.intent,
                                priors, learned, popularityUsers, maxPopularity, dimension);
                    }
                    all.add(new Sample(diff, events.lastEventAt));
                }
            }
        }
        if (all.isEmpty()) {
            return new TrainResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0, null);
        }
        if (all.size() < 2) {
            return new TrainResult(sessions.size(), all.size(), 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0, null);
        }

        all.sort(Comparator.comparing(Sample::time));
        int split = Math.max(1, (int) Math.floor(all.size() * 0.8));
        split = Math.min(split, all.size() - 1);
        List<Sample> train = all.subList(0, split);
        List<Sample> test = all.subList(split, all.size());

        double[] mean = new double[FEATURES.length];
        double[] std = new double[FEATURES.length];
        for (int j = 0; j < FEATURES.length; j++) {
            for (Sample sample : train) {
                mean[j] += sample.features[j];
            }
            mean[j] /= train.size();
            for (Sample sample : train) {
                double delta = sample.features[j] - mean[j];
                std[j] += delta * delta;
            }
            std[j] = Math.sqrt(std[j] / Math.max(1, train.size()));
            if (std[j] < 1e-9) {
                std[j] = 1.0;
            }
        }

        double[] weights = new double[FEATURES.length + 1];
        double learningRate = 0.05;
        double l2 = 0.01;
        for (int epoch = 0; epoch < 3000; epoch++) {
            double[] gradient = new double[weights.length];
            for (Sample sample : train) {
                double logit = weights[0];
                for (int j = 0; j < FEATURES.length; j++) {
                    logit += weights[j + 1] * ((sample.features[j] - mean[j]) / std[j]);
                }
                double probability = sigmoid(logit);
                double error = probability - 1.0;
                gradient[0] += error;
                for (int j = 0; j < FEATURES.length; j++) {
                    gradient[j + 1] += error * ((sample.features[j] - mean[j]) / std[j]);
                }
            }
            for (int j = 0; j < weights.length; j++) {
                double regularization = j == 0 ? 0.0 : l2 * weights[j];
                weights[j] -= learningRate * (gradient[j] / train.size() + regularization);
            }
        }

        Map<String, Double> coefficients = new LinkedHashMap<>();
        double rawIntercept = weights[0];
        for (int j = 0; j < FEATURES.length; j++) {
            double raw = weights[j + 1] / std[j];
            coefficients.put(FEATURES[j], raw);
            rawIntercept -= raw * mean[j];
        }

        double[] scores = new double[test.size()];
        for (int i = 0; i < test.size(); i++) {
            scores[i] = rawScore(test.get(i).features, coefficients, rawIntercept);
        }
        double auc = auc(scores, test.size());
        double accuracy = accuracy(scores);
        double priorAccuracy = positiveRate(test, 0);
        double learnedAccuracy = positiveRate(test, 1);
        double blendAccuracy = positiveRate(test, 2);
        String modelVersion = "pairwise-" + LocalDateTime.now().toString().replace(':', '-');
        writeModel(connection, modelVersion, coefficients, rawIntercept, all.size());
        return new TrainResult(sessions.size(), all.size(), train.size(), test.size(),
                auc, accuracy, test.size(), priorAccuracy, learnedAccuracy, blendAccuracy,
                coefficients, rawIntercept, modelVersion);
    }

    private static double positiveRate(List<Sample> samples, int mode) {
        if (samples == null || samples.isEmpty()) {
            return 0.0;
        }
        int correct = 0;
        for (Sample sample : samples) {
            double score = switch (mode) {
                case 0 -> sample.features[0];
                case 1 -> sample.features[1];
                case 2 -> 0.6 * sample.features[0] + 0.4 * sample.features[1];
                default -> 0.0;
            };
            if (score > 0) {
                correct++;
            }
        }
        return (double) correct / samples.size();
    }

    private static double feature(int index,
                                  String positive,
                                  String negative,
                                  double[] intent,
                                  Map<String, double[]> priors,
                                  Map<String, double[]> learned,
                                  Map<String, Integer> popularityUsers,
                                  int maxPopularity,
                                  int dimension) {
        return switch (index) {
            case 0 -> match(priors.getOrDefault(positive, new double[dimension]), intent)
                    - match(priors.getOrDefault(negative, new double[dimension]), intent);
            case 1 -> match(learned.getOrDefault(positive, new double[dimension]), intent)
                    - match(learned.getOrDefault(negative, new double[dimension]), intent);
            case 2 -> normalizedPopularity(positive, popularityUsers, maxPopularity)
                    - normalizedPopularity(negative, popularityUsers, maxPopularity);
            case 3 -> cosine(priors.getOrDefault(positive, new double[dimension]), intent)
                    - cosine(priors.getOrDefault(negative, new double[dimension]), intent);
            default -> 0.0;
        };
    }

    private static double normalizedPopularity(String poiId,
                                               Map<String, Integer> popularity,
                                               int max) {
        if (max <= 0) {
            return 0.0;
        }
        int users = popularity.getOrDefault(poiId, 0);
        return Math.log1p(users) / Math.log1p(max);
    }

    private static double match(double[] vector, double[] intent) {
        if (vector == null || intent == null) {
            return 0.0;
        }
        int size = Math.min(vector.length, intent.length);
        double sum = 0.0;
        double dot = 0.0;
        for (int i = 0; i < size; i++) {
            sum += intent[i];
            dot += intent[i] * vector[i];
        }
        return sum <= 0.0 ? 0.0 : dot / sum;
    }

    private static double cosine(double[] left, double[] right) {
        if (left == null || right == null) {
            return 0.0;
        }
        int size = Math.min(left.length, right.length);
        double dot = 0.0;
        double a = 0.0;
        double b = 0.0;
        for (int i = 0; i < size; i++) {
            dot += left[i] * right[i];
            a += left[i] * left[i];
            b += right[i] * right[i];
        }
        if (a <= 0.0 || b <= 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(a) * Math.sqrt(b));
    }

    private static double sigmoid(double value) {
        if (value >= 0) {
            double exp = Math.exp(-value);
            return 1.0 / (1.0 + exp);
        }
        double exp = Math.exp(value);
        return exp / (1.0 + exp);
    }

    private static double rawScore(double[] features, Map<String, Double> coefficients, double intercept) {
        double score = intercept;
        for (int j = 0; j < FEATURES.length; j++) {
            score += coefficients.getOrDefault(FEATURES[j], 0.0) * features[j];
        }
        return score;
    }

    private static double auc(double[] scores, int n) {
        if (n == 0) {
            return 0.0;
        }
        // 所有测试样本都是正样本对（chosen > not-chosen），AUC 等价于随机抽一对时正样本分数更高的概率。
        // 这里没有独立负样本，返回 pairwise accuracy 作为可读指标。
        return accuracy(scores);
    }

    private static double accuracy(double[] scores) {
        if (scores.length == 0) {
            return 0.0;
        }
        int correct = 0;
        for (double score : scores) {
            if (score > 0) {
                correct++;
            }
        }
        return (double) correct / scores.length;
    }

    private static Map<Long, Integer> loadDimensions(Connection connection) throws Exception {
        Map<Long, Integer> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tag_id FROM tag_dimension WHERE dim_version = "
                        + "(SELECT MAX(dim_version) FROM tag_dimension WHERE enabled = 1) "
                        + "AND enabled = 1 ORDER BY ordinal")) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getLong(1), result.size());
                }
            }
        }
        return result;
    }

    private static Map<String, double[]> loadPriors(Connection connection,
                                                    Map<Long, Integer> ordinalByTagId,
                                                    int dimension) throws Exception {
        Map<String, double[]> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT poi_id, tag_id, prior_weight FROM attraction_tag")) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Integer ordinal = ordinalByTagId.get(rs.getLong("tag_id"));
                    if (ordinal == null) {
                        continue;
                    }
                    double[] vector = result.computeIfAbsent(rs.getString("poi_id"), key -> new double[dimension]);
                    vector[ordinal] = Math.max(vector[ordinal], clamp(rs.getDouble("prior_weight")));
                }
            }
        }
        return result;
    }

    private static Map<String, double[]> loadLearned(Connection connection,
                                                     Map<String, double[]> priors,
                                                     Map<Long, Integer> ordinalByTagId,
                                                     int dimension) throws Exception {
        Map<String, double[]> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT poi_id, tag_id, pos_count, neg_count FROM attraction_tag_learned")) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Integer ordinal = ordinalByTagId.get(rs.getLong("tag_id"));
                    if (ordinal == null) {
                        continue;
                    }
                    String poiId = rs.getString("poi_id");
                    double[] prior = priors.getOrDefault(poiId, new double[dimension]);
                    double[] vector = result.computeIfAbsent(poiId, key -> new double[dimension]);
                    double pos = rs.getDouble("pos_count");
                    double neg = rs.getDouble("neg_count");
                    double h = (pos + LEARNED_PRIOR_PSEUDO_COUNT * prior[ordinal])
                            / (pos + neg + LEARNED_PRIOR_PSEUDO_COUNT);
                    vector[ordinal] = Math.max(vector[ordinal], clamp(h));
                }
            }
        }
        return result;
    }

    private static Map<String, Integer> loadPopularity(Connection connection) throws Exception {
        Map<String, Integer> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT poi_id, COUNT(DISTINCT account_id) AS users FROM tag_impression "
                        + "WHERE account_id IS NOT NULL AND shown_at >= ? GROUP BY poi_id")) {
            statement.setTimestamp(1, java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(30)));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("poi_id"), rs.getInt("users"));
                }
            }
        }
        return result;
    }

    private static Map<String, SessionEvents> loadSessions(Connection connection) throws Exception {
        Map<String, SessionEvents> result = new LinkedHashMap<>();
        String sql = "SELECT session_key, event_type, poi_id, intent_vector, occurred_at "
                + "FROM tag_feedback_event "
                + "WHERE status IN ('PENDING','APPLIED') AND session_key IS NOT NULL "
                + "AND event_type IN ('ROUTE_STOP_CONFIRM','ROUTE_STOP_REJECT','ROUTE_STOP_NOT_SELECTED') "
                + "ORDER BY occurred_at ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String sessionKey = rs.getString("session_key");
                String type = rs.getString("event_type");
                String poiId = rs.getString("poi_id");
                if (sessionKey == null || poiId == null) {
                    continue;
                }
                SessionEvents events = result.computeIfAbsent(sessionKey, key -> new SessionEvents());
                events.lastEventAt = rs.getTimestamp("occurred_at").toLocalDateTime();
                if ("ROUTE_STOP_CONFIRM".equals(type)) {
                    events.positives.add(poiId);
                    if (events.intent == null) {
                        events.intent = parseIntent(rs.getString("intent_vector"));
                    }
                } else {
                    events.negatives.add(poiId);
                }
            }
        }
        return result;
    }

    private static double[] parseIntent(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) {
                return null;
            }
            double[] vector = new double[root.size()];
            for (int i = 0; i < root.size(); i++) {
                vector[i] = root.get(i).asDouble(0.0);
            }
            return vector;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeModel(Connection connection,
                                   String modelVersion,
                                   Map<String, Double> coefficients,
                                   double intercept,
                                   int samples) throws Exception {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement deactivate = connection.prepareStatement(
                    "UPDATE tag_ranking_model SET active = 0 WHERE active = 1")) {
                deactivate.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO tag_ranking_model"
                            + "(model_version, coefficients, intercept, sample_count, active, trained_at) "
                            + "VALUES (?, ?, ?, ?, 1, NOW())")) {
                insert.setString(1, modelVersion);
                insert.setString(2, MAPPER.writeValueAsString(coefficients));
                insert.setDouble(3, intercept);
                insert.setInt(4, samples);
                insert.executeUpdate();
            }
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Sample(double[] features, LocalDateTime time) {
    }

    private static final class SessionEvents {
        private final Set<String> positives = new LinkedHashSet<>();
        private final Set<String> negatives = new LinkedHashSet<>();
        private double[] intent;
        private LocalDateTime lastEventAt = LocalDateTime.now();
    }

    record TrainResult(int sessions,
                       int samples,
                       int trainSamples,
                       int testSamples,
                       double auc,
                       double accuracy,
                       int testPairs,
                       double priorAccuracy,
                       double learnedAccuracy,
                       double blendAccuracy,
                       Map<String, Double> coefficients,
                       double intercept,
                       String modelVersion) {
    }
}
