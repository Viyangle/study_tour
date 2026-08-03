package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.graph.KnowledgeGraph;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.RecommendedProject;
import com.viyangle.study_tour.service.KnowledgeGraphRecommendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于知识图谱的推荐服务实现。
 *
 * 【首页项目推荐】打分维度：
 *   1. 标签匹配（用户偏好标签 vs 项目路线上的景点标签）  → 权重 40
 *   2. 地区匹配（用户所在地 vs 项目所在地）              → 权重 10
 *   3. 协同过滤（相似用户参与了该项目）                  → 权重 20
 *   4. 项目评分（评价均分）                             → 权重 45
 *   + 时间衰减因子
 *   + MMR 多样性控制
 *
 * 【AI路线规划召回】策略：
 *   1. 标签匹配：从图谱中找匹配标签的景点
 *   2. 地区过滤：优先同地区的景点
 *   3. 相邻扩展：从种子景点沿相邻边扩展，找到可以串成线的景点
 */
@Slf4j
@Service
public class KnowledgeGraphRecommendServiceImpl implements KnowledgeGraphRecommendService {

    /** 项目推荐各维度权重 */
    private static final double W_REGION_MATCH = 5.0;  // 降低地区权重，改为软约束
    private static final double W_COLLABORATIVE = 20.0;
    private static final double W_REVIEW_SCORE = 10.0;
    private static final double W_PAGERANK = 45.0;

    /** 相邻扩展跳数 */
    private static final int EXPAND_HOPS = 2;

    /** 时间衰减系数（每天衰减 1%） */
    private static final double TIME_DECAY_RATE = 0.01;

    /** MMR 多样性控制参数：lambda 越大越注重相关性，越小越注重多样性 */
    private static final double MMR_LAMBDA = 0.7;

    @Autowired
    private KnowledgeGraph graph;

    @Override
    public List<Project> recommendProjects(Long accountId, int limit) {
        if (!graph.isLoaded()) {
            log.warn("知识图谱未加载，返回空推荐");
            return List.of();
        }

        int maxLimit = limit <= 0 ? 10 : limit;

        // 获取用户偏好信息
        List<String> userTags = accountId != null ? graph.getUserTagPrefs(accountId) : List.of();
        String userRegion = accountId != null ? graph.getUserRegion(accountId) : null;

        // 冷启动判断：用户无标签偏好时走热度兜底
        boolean isColdStart = userTags.isEmpty();

        if (isColdStart) {
            return coldStartRecommend(maxLimit);
        }

        // 找到相似用户（标签有交集的用户参与的项目）
        Set<Long> similarUserProjects = new LinkedHashSet<>();
        if (accountId != null) {
            similarUserProjects = findSimilarUserProjects(accountId, userTags);
        }

        // 预计算 Personalized PageRank
        Set<String> personalNodes = graph.getAttractionsByTags(userTags);
        Map<String, Double> pprScores = graph.personalizedPageRank(personalNodes, 30, 0.85);

        // 对所有可推荐项目打分
        Map<Long, Double> scores = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (Long projectId : graph.getAllProjectIds()) {
            Project project = graph.getProject(projectId);
            if (project == null) continue;

            // 只推荐状态为 OPEN 或 MATCHING 的项目
            String status = project.getStatus();
            if (!"OPEN".equals(status) && !"MATCHING".equals(status)) {
                continue;
            }

            double score = 0;

            // 1. 地区匹配 (软约束)
            score += W_REGION_MATCH * calcRegionMatchScore(projectId, userRegion);

            // 2. 协同过滤
            if (similarUserProjects.contains(projectId)) {
                score += W_COLLABORATIVE;
            }

            // 3. 项目评分 (软约束)
            double avgScore = graph.getProjectAvgScore(projectId);
            if (avgScore > 0) {
                score += W_REVIEW_SCORE * (avgScore / 5.0);
            }

            // 4. PageRank 图传播得分
            score += W_PAGERANK * calcPPRScore(projectId, pprScores);

            // 6. 时间衰减因子
            score *= calcTimeDecay(project, now);

            if (score > 0) {
                scores.put(projectId, score);
            }
        }

        // MMR 多样性排序
        List<Long> sortedProjectIds = mmrSelect(scores, maxLimit);

        List<Project> result = new ArrayList<>();
        for (Long pid : sortedProjectIds) {
            Project p = graph.getProject(pid);
            if (p != null) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * 冷启动推荐：用户无偏好时，按热度（参与人数）+ 评分排序。
     */
    private List<Project> coldStartRecommend(int limit) {
        Map<Long, Double> hotScores = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();

        for (Long projectId : graph.getAllProjectIds()) {
            Project project = graph.getProject(projectId);
            if (project == null) continue;
            String status = project.getStatus();
            if (!"OPEN".equals(status) && !"MATCHING".equals(status)) continue;

            double score = 0;
            // 热度：参与人数归一化
            int memberCount = graph.getProjectMemberCount(projectId);
            score += 30.0 * Math.min(1.0, memberCount / 20.0);
            // 评分
            double avgScore = graph.getProjectAvgScore(projectId);
            if (avgScore > 0) {
                score += 40.0 * (avgScore / 5.0);
            }
            // 无评分时给一个基础分，让新项目有机会曝光
            if (avgScore == 0) {
                score += 20.0;
            }
            // 时间衰减
            score *= calcTimeDecay(project, now);

            if (score > 0) {
                hotScores.put(projectId, score);
            }
        }

        List<Long> sorted = hotScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(limit)
                .collect(Collectors.toList());

        List<Project> result = new ArrayList<>();
        for (Long pid : sorted) {
            Project p = graph.getProject(pid);
            if (p != null) result.add(p);
        }
        return result;
    }

    /**
     * 时间衰减因子：项目创建越久，得分越低。
     * 公式：1.0 / (1.0 + days * decayRate)
     */
    private double calcTimeDecay(Project project, LocalDateTime now) {
        LocalDateTime createdAt = project.getCreatedAt();
        if (createdAt == null) return 1.0;
        long days = ChronoUnit.DAYS.between(createdAt, now);
        if (days < 0) days = 0;
        return 1.0 / (1.0 + days * TIME_DECAY_RATE);
    }

    /**
     * MMR（Maximal Marginal Relevance）多样性选择。
     * 每次选取得分最高的项目，然后惩罚与已选项目相似度过高的候选。
     */
    private List<Long> mmrSelect(Map<Long, Double> scores, int limit) {
        if (scores.size() <= limit) {
            return scores.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }

        // 预计算每个项目的标签集合（用于相似度计算）
        Map<Long, Set<String>> projectTagSets = new HashMap<>();
        for (Long pid : scores.keySet()) {
            projectTagSets.put(pid, getProjectRouteTagSet(pid));
        }

        Set<Long> selected = new LinkedHashSet<>();
        Set<Long> remaining = new LinkedHashSet<>(scores.keySet());

        while (selected.size() < limit && !remaining.isEmpty()) {
            double bestMMR = Double.NEGATIVE_INFINITY;
            Long bestId = null;

            for (Long candidate : remaining) {
                double relevance = scores.get(candidate);
                // 与已选项目的最大相似度
                double maxSim = 0;
                for (Long sel : selected) {
                    double sim = jaccardSimilarity(projectTagSets.get(candidate), projectTagSets.get(sel));
                    maxSim = Math.max(maxSim, sim);
                }
                double mmr = MMR_LAMBDA * relevance - (1 - MMR_LAMBDA) * maxSim * 100; // 100为缩放因子
                if (mmr > bestMMR) {
                    bestMMR = mmr;
                    bestId = candidate;
                }
            }

            if (bestId == null) break;
            selected.add(bestId);
            remaining.remove(bestId);
        }

        return new ArrayList<>(selected);
    }

    /**
     * 获取项目路线上所有景点的标签集合。
     */
    private Set<String> getProjectRouteTagSet(Long projectId) {
        Set<String> tagSet = new LinkedHashSet<>();
        Long routeId = graph.getProjectRoute(projectId);
        if (routeId == null) return tagSet;
        List<String> pois = graph.getRouteAttractions(routeId);
        for (String poiId : pois) {
            tagSet.addAll(graph.getAttractionTags(poiId));
        }
        return tagSet;
    }

    /**
     * Jaccard 相似度：两个集合的交集大小 / 并集大小。
     */
    private double jaccardSimilarity(Set<String> setA, Set<String> setB) {
        if (setA.isEmpty() && setB.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return (double) intersection.size() / union.size();
    }

    /**
     * PPR 得分：取项目路线上所有景点的 PPR 分数之和，归一化到 0-1。
     */
    private double calcPPRScore(Long projectId, Map<String, Double> pprScores) {
        if (pprScores == null || pprScores.isEmpty()) return 0.0;
        Long routeId = graph.getProjectRoute(projectId);
        if (routeId == null) return 0.0;
        List<String> pois = graph.getRouteAttractions(routeId);
        if (pois.isEmpty()) return 0.0;

        double sum = 0;
        for (String poiId : pois) {
            sum += pprScores.getOrDefault(poiId, 0.0);
        }
        // 归一化：除以景点数量，再乘以一个缩放因子
        double avg = sum / pois.size();
        // PPR 值通常很小，放大到 0-1 范围
        return Math.min(1.0, avg * pois.size() * 10);
    }

    @Override
    public List<Attraction> retrieveCandidatesByGraph(List<String> tagNames, String regionCode, int limit) {
        if (!graph.isLoaded()) {
            log.warn("知识图谱未加载，返回空候选");
            return List.of();
        }

        int maxLimit = limit <= 0 ? 20 : limit;

        // 第1步：按标签找到种子景点
        Set<String> seedPoiIds;
        if (tagNames != null && !tagNames.isEmpty()) {
            seedPoiIds = graph.getAttractionsByTags(tagNames);
        } else {
            seedPoiIds = new LinkedHashSet<>(graph.getAllAttractionPoiIds());
        }

        // 第2步：地区过滤（优先同地区，但不强制）
        List<String> regionSeeds = new ArrayList<>();
        List<String> otherSeeds = new ArrayList<>();
        if (regionCode != null && !regionCode.isBlank()) {
            String regionPrefix = regionCode.length() >= 4 ? regionCode.substring(0, 4) : regionCode;
            for (String poiId : seedPoiIds) {
                String region = graph.getAttractionRegion(poiId);
                if (region != null && region.startsWith(regionPrefix)) {
                    regionSeeds.add(poiId);
                } else {
                    otherSeeds.add(poiId);
                }
            }
        } else {
            regionSeeds.addAll(seedPoiIds);
        }

        // 第3步：从种子景点沿相邻边扩展
        Set<String> expanded;
        if (!regionSeeds.isEmpty()) {
            expanded = graph.expandNeighbors(regionSeeds, EXPAND_HOPS);
        } else if (!otherSeeds.isEmpty()) {
            expanded = graph.expandNeighbors(otherSeeds, EXPAND_HOPS);
        } else {
            expanded = seedPoiIds;
        }

        // 第4步：排序——同地区优先，标签匹配多优先
        List<String> sortedPoiIds = new ArrayList<>(expanded);
        sortedPoiIds.sort((a, b) -> {
            int scoreA = calcCandidateScore(a, tagNames, regionCode);
            int scoreB = calcCandidateScore(b, tagNames, regionCode);
            return Integer.compare(scoreB, scoreA); // 降序
        });

        // 第5步：截取并加载景点详情
        List<Attraction> result = new ArrayList<>();
        for (String poiId : sortedPoiIds) {
            if (result.size() >= maxLimit) break;
            Attraction a = graph.getAttraction(poiId);
            if (a != null && a.getLocation() != null && !a.getLocation().isBlank()) {
                result.add(a);
            }
        }
        return result;
    }

    // ==================== 内部打分方法 ====================

    /**
     * 标签匹配得分：项目路线上的景点标签与用户偏好的重合度。
     * 返回 0.0 ~ 1.0
     */
    private double calcTagMatchScore(Long projectId, List<String> userTags) {
        if (userTags == null || userTags.isEmpty()) {
            return 0.0;
        }

        Long routeId = graph.getProjectRoute(projectId);
        if (routeId == null) return 0.0;

        List<String> routeAttractions = graph.getRouteAttractions(routeId);
        if (routeAttractions.isEmpty()) return 0.0;

        // 收集路线上所有景点的标签
        Set<String> routeTags = new LinkedHashSet<>();
        for (String poiId : routeAttractions) {
            routeTags.addAll(graph.getAttractionTags(poiId));
        }

        // 计算重合度
        long matchCount = userTags.stream().filter(routeTags::contains).count();
        return routeTags.isEmpty() ? 0.0 : (double) matchCount / userTags.size();
    }

    /**
     * 地区匹配得分：项目所在地区与用户所在地的匹配程度。
     * 返回 0.0 ~ 1.0
     */
    private double calcRegionMatchScore(Long projectId, String userRegion) {
        if (userRegion == null || userRegion.isBlank()) {
            return 0.0;
        }

        Project project = graph.getProject(projectId);
        if (project == null || project.getRegionAdcode() == null) return 0.0;

        String projectRegion = project.getRegionAdcode();

        // 完全匹配（同区）
        if (projectRegion.equals(userRegion)) return 1.0;

        // 同市（adcode 前4位相同）
        if (projectRegion.length() >= 4 && userRegion.length() >= 4
                && projectRegion.substring(0, 4).equals(userRegion.substring(0, 4))) {
            return 0.7;
        }

        // 同省（adcode 前2位相同）
        if (projectRegion.length() >= 2 && userRegion.length() >= 2
                && projectRegion.substring(0, 2).equals(userRegion.substring(0, 2))) {
            return 0.3;
        }

        return 0.0;
    }

    /**
     * 找相似用户参与的项目（协同过滤）。
     * 使用余弦相似度全局扫描有标签偏好的用户，取 Top-K 最相似用户。
     */
    private static final int SIMILAR_USER_TOP_K = 20;

    private Set<Long> findSimilarUserProjects(Long accountId, List<String> userTags) {
        Set<Long> result = new LinkedHashSet<>();
        Set<String> userTagSet = new HashSet<>(userTags);
        List<Long> userProjects = graph.getUserProjects(accountId);
        Set<Long> myProjectSet = new HashSet<>(userProjects);

        // 全局扫描有标签偏好的用户，计算余弦相似度
        List<Long> candidateUsers = graph.getUsersWithTagPrefs();
        List<Map.Entry<Long, Double>> similarities = new ArrayList<>();

        for (Long otherUserId : candidateUsers) {
            if (otherUserId.equals(accountId)) continue;
            List<String> otherTags = graph.getUserTagPrefs(otherUserId);
            if (otherTags.isEmpty()) continue;

            double sim = cosineSimilarity(userTagSet, new HashSet<>(otherTags));
            if (sim > 0) {
                similarities.add(Map.entry(otherUserId, sim));
            }
        }

        // 取 Top-K 最相似用户
        similarities.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int topK = Math.min(SIMILAR_USER_TOP_K, similarities.size());
        for (int i = 0; i < topK; i++) {
            Long similarUserId = similarities.get(i).getKey();
            result.addAll(graph.getUserProjects(similarUserId));
        }

        // 排除用户已参与的项目
        result.removeAll(myProjectSet);
        return result;
    }

    /**
     * 余弦相似度：两个标签集合的相似度。
     * 将每个标签视为一个维度，值为 1（有）或 0（无）。
     */
    private double cosineSimilarity(Set<String> setA, Set<String> setB) {
        if (setA.isEmpty() || setB.isEmpty()) return 0.0;
        Set<String> allTags = new HashSet<>(setA);
        allTags.addAll(setB);

        double dotProduct = 0;
        double normA = 0;
        double normB = 0;

        for (String tag : allTags) {
            double a = setA.contains(tag) ? 1.0 : 0.0;
            double b = setB.contains(tag) ? 1.0 : 0.0;
            dotProduct += a * b;
            normA += a * a;
            normB += b * b;
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0 ? 0.0 : dotProduct / denominator;
    }

    /**
     * 候选景点打分（用于排序）。
     * 同地区 +100，标签匹配每个 +50，有相邻关系 +10
     */
    private int calcCandidateScore(String poiId, List<String> tagNames, String regionCode) {
        int score = 0;

        // 地区匹配
        if (regionCode != null && !regionCode.isBlank()) {
            String region = graph.getAttractionRegion(poiId);
            if (region != null) {
                String prefix = regionCode.length() >= 4 ? regionCode.substring(0, 4) : regionCode;
                if (region.startsWith(prefix)) {
                    score += 100;
                } else if (region.length() >= 2 && regionCode.length() >= 2
                        && region.substring(0, 2).equals(regionCode.substring(0, 2))) {
                    score += 30;
                }
            }
        }

        // 标签匹配
        if (tagNames != null && !tagNames.isEmpty()) {
            List<String> attractionTags = graph.getAttractionTags(poiId);
            for (String tag : tagNames) {
                if (attractionTags.contains(tag)) {
                    score += 50;
                }
            }
        }

        return score;
    }

    @Override
    public List<RecommendedProject> recommendWithExplanation(Long accountId, int limit) {
        return recommendWithExplanation(accountId, null, limit);
    }

    @Override
    public List<RecommendedProject> recommendWithExplanation(Long accountId, String keyword, int limit) {
        if (!graph.isLoaded()) {
            log.warn("知识图谱未加载，返回空推荐");
            return List.of();
        }

        int maxLimit = limit <= 0 ? 10 : limit;
        List<String> userTags = accountId != null ? graph.getUserTagPrefs(accountId) : List.of();
        String userRegion = accountId != null ? graph.getUserRegion(accountId) : null;

        String normalizedKeyword = keyword == null ? null : keyword.trim().toLowerCase(Locale.ROOT);
        List<Project> projects;
        if (normalizedKeyword == null || normalizedKeyword.isEmpty()) {
            projects = recommendProjects(accountId, maxLimit);
        } else {
            // 先取得完整的推荐候选，再过滤，避免关键字结果被推荐数量上限提前截断。
            projects = recommendProjects(accountId, Integer.MAX_VALUE).stream()
                    .filter(project -> matchesKeyword(project, normalizedKeyword))
                    .limit(maxLimit)
                    .collect(Collectors.toList());
        }

        // 为每个项目生成解释
        List<RecommendedProject> result = new ArrayList<>();
        Set<Long> similarUserProjects = (accountId != null && !userTags.isEmpty())
                ? findSimilarUserProjects(accountId, userTags) : Set.of();

        for (Project project : projects) {
            List<String> reasons = generateReasons(project, userTags, userRegion, similarUserProjects);
            double score = 0; // 简化：不重新计算分数
            result.add(new RecommendedProject(project, score, reasons));
        }
        return result;
    }

    private boolean matchesKeyword(Project project, String normalizedKeyword) {
        return containsKeyword(project.getTitle(), normalizedKeyword)
                || containsKeyword(project.getTag(), normalizedKeyword)
                || containsKeyword(project.getStatus(), normalizedKeyword)
                || containsKeyword(project.getStartPoint(), normalizedKeyword)
                || containsKeyword(project.getLeaderRequirements(), normalizedKeyword)
                || containsKeyword(project.getParticipantRequirements(), normalizedKeyword);
    }

    private boolean containsKeyword(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    /**
     * 生成推荐解释文案。
     */
    private List<String> generateReasons(Project project, List<String> userTags,
                                          String userRegion, Set<Long> similarUserProjects) {
        List<String> reasons = new ArrayList<>();

        // 1. 标签匹配解释
        if (!userTags.isEmpty()) {
            Long routeId = graph.getProjectRoute(project.getId());
            if (routeId != null) {
                List<String> routePois = graph.getRouteAttractions(routeId);
                Set<String> matchedTags = new LinkedHashSet<>();
                List<String> matchedAttractions = new ArrayList<>();

                for (String poiId : routePois) {
                    List<String> attractionTags = graph.getAttractionTags(poiId);
                    for (String tag : userTags) {
                        if (attractionTags.contains(tag)) {
                            matchedTags.add(tag);
                            Attraction a = graph.getAttraction(poiId);
                            if (a != null && !matchedAttractions.contains(a.getName()) && matchedAttractions.size() < 2) {
                                matchedAttractions.add(a.getName());
                            }
                        }
                    }
                }

                if (!matchedTags.isEmpty()) {
                    String tagStr = String.join("、", matchedTags);
                    String attrStr = matchedAttractions.isEmpty() ? "" : "（如" + String.join("、", matchedAttractions) + "）";
                    reasons.add("因为你偏好[" + tagStr + "]，该项目路线包含相关景点" + attrStr);
                }
            }
        }

        // 2. 协同过滤解释
        if (similarUserProjects.contains(project.getId())) {
            reasons.add("与你兴趣相似的人也参与了该项目");
        }

        // 3. 评分解释
        double avgScore = graph.getProjectAvgScore(project.getId());
        if (avgScore >= 4.0) {
            reasons.add("该项目口碑很好，平均评分" + String.format("%.1f", avgScore) + "分");
        }

        // 4. 地区匹配解释
        if (userRegion != null && !userRegion.isBlank() && project.getRegionAdcode() != null) {
            String projRegion = project.getRegionAdcode();
            if (projRegion.length() >= 2 && userRegion.length() >= 2
                    && projRegion.substring(0, 2).equals(userRegion.substring(0, 2))) {
                reasons.add("项目就在你所在省份，出行方便");
            }
        }

        // 5. 相邻扩展解释：路线上是否有用户偏好景点的"邻居"
        if (!userTags.isEmpty()) {
            Set<String> userTagAttractions = graph.getAttractionsByTags(userTags);
            Long routeId = graph.getProjectRoute(project.getId());
            if (routeId != null) {
                List<String> routePois = graph.getRouteAttractions(routeId);
                for (String poiId : routePois) {
                    // 如果路线上的景点本身就是用户兴趣点，跳过（避免闭环）
                    if (userTagAttractions.contains(poiId)) continue;
                    
                    List<String> neighbors = graph.getNeighbors(poiId);
                    for (String neighbor : neighbors) {
                        if (userTagAttractions.contains(neighbor)) {
                            Attraction a = graph.getAttraction(neighbor);
                            Attraction routeA = graph.getAttraction(poiId);
                            if (a != null && routeA != null) {
                                reasons.add("路线上的" + routeA.getName() + "靠近你感兴趣的" + a.getName());
                                break;
                            }
                        }
                    }
                    if (reasons.size() >= 5) break;
                }
            }
        }

        // 兑底：无特定理由时给通用文案
        if (reasons.isEmpty()) {
            reasons.add("热门项目，值得一试");
        }

        return reasons;
    }
}
