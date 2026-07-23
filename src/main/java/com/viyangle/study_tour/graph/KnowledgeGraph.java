package com.viyangle.study_tour.graph;

import com.viyangle.study_tour.mapper.*;
import com.viyangle.study_tour.pojo.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存知识图谱。
 *
 * 启动时从 MySQL 加载所有节点和边，构建图结构。
 * 提供图上的查询方法，供推荐服务使用。
 *
 * 节点类型：ATTRACTION / TAG / REGION / ROUTE / PROJECT / ACCOUNT
 * 边类型：
 *   ATTRACTION → TAG       （景点适合什么研学主题）
 *   ATTRACTION → REGION    （景点属于哪个地区）
 *   ATTRACTION → ATTRACTION（相邻景点，直线距离10km）
 *   ROUTE → ATTRACTION     （路线包含哪些景点）
 *   PROJECT → ROUTE        （项目用了哪条路线）
 *   ACCOUNT → TAG          （用户偏好标签）
 *   ACCOUNT → PROJECT      （用户参与了哪个项目）
 *   ACCOUNT → PROJECT      （用户评价了哪个项目，带分数）
 */
@Slf4j
@Component
public class KnowledgeGraph {

    @Autowired
    private AttractionMapper attractionMapper;

    @Autowired
    private AttractionTagMapper attractionTagMapper;

    @Autowired
    private AttractionAdjacencyMapper adjacencyMapper;

    @Autowired
    private TagMapper tagMapper;

    @Autowired
    private RouteAttractionMapper routeAttractionMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private ProjectMemberMapper projectMemberMapper;

    @Autowired
    private AccountTagPrefMapper accountTagPrefMapper;

    @Autowired
    private ReviewMapper reviewMapper;

    @Autowired
    private AccountMapper accountMapper;

    // ========== 节点存储 ==========
    private final Map<String, Attraction> attractions = new ConcurrentHashMap<>();           // poiId → Attraction
    private final Map<Long, Tag> tags = new ConcurrentHashMap<>();                          // tagId → Tag
    private final Map<String, String> attractionRegion = new ConcurrentHashMap<>();          // poiId → adcode
    private final Map<Long, Project> projects = new ConcurrentHashMap<>();                  // projectId → Project

    // ========== 边存储（邻接表） ==========

    /** 景点 → 标签名列表 */
    private final Map<String, List<String>> attractionToTags = new ConcurrentHashMap<>();

    /** 标签名 → 景点poiId列表 */
    private final Map<String, List<String>> tagToAttractions = new ConcurrentHashMap<>();

    /** 景点 → 相邻景点poiId列表 */
    private final Map<String, List<String>> attractionNeighbors = new ConcurrentHashMap<>();

    /** 路线ID → 景点poiId列表 */
    private final Map<Long, List<String>> routeToAttractions = new ConcurrentHashMap<>();

    /** 景点poiId → 包含该景点的路线ID列表 */
    private final Map<String, List<Long>> attractionToRoutes = new ConcurrentHashMap<>();

    /** 项目ID → 路线ID */
    private final Map<Long, Long> projectToRoute = new ConcurrentHashMap<>();

    /** 路线ID → 项目ID列表 */
    private final Map<Long, List<Long>> routeToProjects = new ConcurrentHashMap<>();

    /** 用户ID → 偏好标签名列表 */
    private final Map<Long, List<String>> userTagPrefs = new ConcurrentHashMap<>();

    /** 用户ID → 参与的项目ID列表 */
    private final Map<Long, List<Long>> userProjects = new ConcurrentHashMap<>();

    /** 项目ID → 参与者用户ID列表 */
    private final Map<Long, List<Long>> projectMembers = new ConcurrentHashMap<>();

    /** 用户ID → 评价的项目ID→分数 */
    private final Map<Long, Map<Long, Integer>> userReviews = new ConcurrentHashMap<>();

    /** 项目ID → 平均评分 */
    private final Map<Long, Double> projectAvgScore = new ConcurrentHashMap<>();

    /** 用户ID → 地区adcode */
    private final Map<Long, String> accountRegion = new ConcurrentHashMap<>();

    /** 所有有标签偏好的用户ID列表（用于协同过滤） */
    private final List<Long> usersWithTagPrefs = new ArrayList<>();

    private volatile boolean loaded = false;

    /**
     * 启动时加载图数据。
     */
    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            log.warn("知识图谱初始化加载失败，推荐功能可能不可用: {}", e.getMessage());
        }
    }

    /**
     * 重新加载所有图数据（可供外部调用刷新）。
     */
    public void reload() {
        log.info("知识图谱: 开始加载图数据...");
        long start = System.currentTimeMillis();

        loadAttractions();
        loadTags();
        loadAttractionTags();
        loadAdjacency();
        loadRouteAttractions();
        loadProjects();
        loadUserTagPrefs();
        loadProjectMembers();
        loadReviews();
        loadAccountRegions();

        loaded = true;
        long elapsed = System.currentTimeMillis() - start;
        log.info("知识图谱: 加载完成, 耗时 {}ms, 景点={}, 标签={}, 项目={}, 相邻关系={}条",
                elapsed, attractions.size(), tags.size(), projects.size(), attractionNeighbors.size());
    }

    public boolean isLoaded() {
        return loaded;
    }

    // ==================== 图查询方法 ====================

    /**
     * 获取某景点的所有研学标签名称。
     */
    public List<String> getAttractionTags(String poiId) {
        return attractionToTags.getOrDefault(poiId, List.of());
    }

    /**
     * 获取某标签下的所有景点poiId。
     */
    public List<String> getAttractionsByTag(String tagName) {
        return tagToAttractions.getOrDefault(tagName, List.of());
    }

    /**
     * 获取多个标签下的所有景点poiId（去重）。
     */
    public Set<String> getAttractionsByTags(Collection<String> tagNames) {
        Set<String> result = new LinkedHashSet<>();
        for (String tag : tagNames) {
            List<String> pois = tagToAttractions.get(tag);
            if (pois != null) {
                result.addAll(pois);
            }
        }
        return result;
    }

    /**
     * 获取某景点的相邻景点poiId。
     */
    public List<String> getNeighbors(String poiId) {
        return attractionNeighbors.getOrDefault(poiId, List.of());
    }

    /**
     * 从种子景点出发，沿相邻边扩展 N 跳，收集所有可达景点。
     */
    public Set<String> expandNeighbors(Collection<String> seedPoiIds, int hops) {
        Set<String> visited = new LinkedHashSet<>(seedPoiIds);
        Set<String> current = new LinkedHashSet<>(seedPoiIds);
        for (int i = 0; i < hops; i++) {
            Set<String> next = new LinkedHashSet<>();
            for (String poiId : current) {
                for (String neighbor : getNeighbors(poiId)) {
                    if (visited.add(neighbor)) {
                        next.add(neighbor);
                    }
                }
            }
            current = next;
            if (current.isEmpty()) break;
        }
        return visited;
    }

    /**
     * 获取某路线包含的景点poiId列表。
     */
    public List<String> getRouteAttractions(Long routeId) {
        return routeToAttractions.getOrDefault(routeId, List.of());
    }

    /**
     * 获取包含某景点的所有路线ID。
     */
    public List<Long> getRoutesByAttraction(String poiId) {
        return attractionToRoutes.getOrDefault(poiId, List.of());
    }

    /**
     * 获取项目对应的路线ID。
     */
    public Long getProjectRoute(Long projectId) {
        return projectToRoute.get(projectId);
    }

    /**
     * 获取某路线关联的所有项目ID。
     */
    public List<Long> getProjectsByRoute(Long routeId) {
        return routeToProjects.getOrDefault(routeId, List.of());
    }

    /**
     * 获取用户的偏好标签名列表。
     */
    public List<String> getUserTagPrefs(Long accountId) {
        return userTagPrefs.getOrDefault(accountId, List.of());
    }

    /**
     * 获取用户参与的项目ID列表。
     */
    public List<Long> getUserProjects(Long accountId) {
        return userProjects.getOrDefault(accountId, List.of());
    }

    /**
     * 获取项目的参与者用户ID列表。
     */
    public List<Long> getProjectMembers(Long projectId) {
        return projectMembers.getOrDefault(projectId, List.of());
    }

    /**
     * 获取项目的平均评分（无评价返回 0）。
     */
    public double getProjectAvgScore(Long projectId) {
        return projectAvgScore.getOrDefault(projectId, 0.0);
    }

    /**
     * 获取景点详情。
     */
    public Attraction getAttraction(String poiId) {
        return attractions.get(poiId);
    }

    /**
     * 获取项目详情。
     */
    public Project getProject(Long projectId) {
        return projects.get(projectId);
    }

    /**
     * 获取所有项目ID。
     */
    public Set<Long> getAllProjectIds() {
        return Collections.unmodifiableSet(projects.keySet());
    }

    /**
     * 获取所有景点POI ID。
     */
    public Set<String> getAllAttractionPoiIds() {
        return Collections.unmodifiableSet(attractions.keySet());
    }

    /**
     * 获取景点所属地区adcode。
     */
    public String getAttractionRegion(String poiId) {
        return attractionRegion.get(poiId);
    }

    /**
     * 获取用户所在地区adcode。
     */
    public String getUserRegion(Long accountId) {
        return accountRegion.get(accountId);
    }

    /**
     * 获取所有有标签偏好的用户ID列表。
     */
    public List<Long> getUsersWithTagPrefs() {
        return Collections.unmodifiableList(usersWithTagPrefs);
    }

    /**
     * 获取项目的参与者数量（热度指标）。
     */
    public int getProjectMemberCount(Long projectId) {
        List<Long> members = projectMembers.get(projectId);
        return members != null ? members.size() : 0;
    }

    /**
     * Personalized PageRank 随机游走。
     * 从用户偏好节点出发，在景点图上执行随机游走，返回每个景点的稳态概率。
     *
     * 转移权重：
     *   - 景点→相邻景点（权重 0.4）
     *   - 景点→同标签景点（权重 0.3）
     *   - 景点→同路线上其他景点（权重 0.3）
     *
     * @param personalNodes 个人化种子节点（用户偏好标签对应的景点poiId）
     * @param maxIterations 最大迭代次数
     * @param dampingFactor 阻尼系数（通常 0.85）
     * @return poiId → PPR 分数
     */
    public Map<String, Double> personalizedPageRank(Collection<String> personalNodes, int maxIterations, double dampingFactor) {
        Map<String, Double> scores = new HashMap<>();
        if (personalNodes == null || personalNodes.isEmpty()) {
            return scores;
        }

        Set<String> allPoiIds = getAllAttractionPoiIds();
        if (allPoiIds.isEmpty()) return scores;

        // 初始化：个人化节点均匀分布
        double initVal = 1.0 / personalNodes.size();
        for (String poiId : allPoiIds) {
            scores.put(poiId, personalNodes.contains(poiId) ? initVal : 0.0);
        }

        // 预构建每个节点的出边和权重
        Map<String, List<String>> outEdges = new HashMap<>();
        Map<String, List<Double>> outWeights = new HashMap<>();

        for (String poiId : allPoiIds) {
            List<String> neighbors = new ArrayList<>();
            List<Double> weights = new ArrayList<>();

            // 相邻景点（权重 0.4）
            List<String> adj = getNeighbors(poiId);
            if (!adj.isEmpty()) {
                neighbors.addAll(adj);
                for (int i = 0; i < adj.size(); i++) {
                    weights.add(0.4 / adj.size());
                }
            }

            // 同标签景点（权重 0.3）
            List<String> myTags = getAttractionTags(poiId);
            Set<String> sameTagPois = new LinkedHashSet<>();
            for (String tag : myTags) {
                sameTagPois.addAll(getAttractionsByTag(tag));
            }
            sameTagPois.remove(poiId);
            if (!sameTagPois.isEmpty()) {
                neighbors.addAll(sameTagPois);
                for (int i = 0; i < sameTagPois.size(); i++) {
                    weights.add(0.3 / sameTagPois.size());
                }
            }

            // 同路线上其他景点（权重 0.3）
            List<Long> myRoutes = getRoutesByAttraction(poiId);
            Set<String> routePois = new LinkedHashSet<>();
            for (Long routeId : myRoutes) {
                routePois.addAll(getRouteAttractions(routeId));
            }
            routePois.remove(poiId);
            if (!routePois.isEmpty()) {
                neighbors.addAll(routePois);
                for (int i = 0; i < routePois.size(); i++) {
                    weights.add(0.3 / routePois.size());
                }
            }

            outEdges.put(poiId, neighbors);
            outWeights.put(poiId, weights);
        }

        // 迭代计算
        double teleport = (1 - dampingFactor) / personalNodes.size();
        for (int iter = 0; iter < maxIterations; iter++) {
            Map<String, Double> newScores = new HashMap<>();
            for (String poiId : allPoiIds) {
                newScores.put(poiId, personalNodes.contains(poiId) ? teleport : 0.0);
            }

            for (String fromPoi : allPoiIds) {
                List<String> neighbors = outEdges.get(fromPoi);
                List<Double> weights = outWeights.get(fromPoi);
                if (neighbors == null || neighbors.isEmpty()) continue;

                double fromScore = scores.get(fromPoi);
                for (int i = 0; i < neighbors.size(); i++) {
                    String toPoi = neighbors.get(i);
                    double w = weights.get(i);
                    newScores.merge(toPoi, dampingFactor * fromScore * w, Double::sum);
                }
            }

            // 检查收敛
            double diff = 0;
            for (String poiId : allPoiIds) {
                diff += Math.abs(newScores.get(poiId) - scores.get(poiId));
            }
            scores = newScores;
            if (diff < 1e-6) {
                log.debug("PPR 在第 {} 次迭代收敛", iter + 1);
                break;
            }
        }

        return scores;
    }

    // ==================== 加载方法 ====================

    private void loadAttractions() {
        attractions.clear();
        attractionRegion.clear();
        List<Attraction> list = attractionMapper.selectAll();
        if (list != null) {
            for (Attraction a : list) {
                if (a != null && a.getPoiId() != null) {
                    attractions.put(a.getPoiId(), a);
                    if (a.getAdcode() != null) {
                        attractionRegion.put(a.getPoiId(), a.getAdcode());
                    }
                }
            }
        }
    }

    private void loadTags() {
        tags.clear();
        List<Tag> list = tagMapper.selectAll();
        if (list != null) {
            for (Tag t : list) {
                if (t != null && t.getId() != null) {
                    tags.put(t.getId(), t);
                }
            }
        }
    }

    private void loadAttractionTags() {
        attractionToTags.clear();
        tagToAttractions.clear();
        List<AttractionTag> list = attractionTagMapper.selectAll();
        if (list != null) {
            for (AttractionTag at : list) {
                if (at == null || at.getPoiId() == null || at.getTagId() == null) continue;
                Tag tag = tags.get(at.getTagId());
                if (tag == null) continue;
                String tagName = tag.getName();

                attractionToTags.computeIfAbsent(at.getPoiId(), k -> new ArrayList<>()).add(tagName);
                tagToAttractions.computeIfAbsent(tagName, k -> new ArrayList<>()).add(at.getPoiId());
            }
        }
    }

    private void loadAdjacency() {
        attractionNeighbors.clear();
        List<AttractionAdjacency> list = adjacencyMapper.selectAll();
        if (list != null) {
            for (AttractionAdjacency adj : list) {
                if (adj == null || adj.getFromPoiId() == null || adj.getToPoiId() == null) continue;
                // 跳过自引用的占位记录（from_poi_id == to_poi_id 表示该景点已计算过但无有效邻居）
                if (adj.getFromPoiId().equals(adj.getToPoiId())) continue;
                attractionNeighbors.computeIfAbsent(adj.getFromPoiId(), k -> new ArrayList<>())
                        .add(adj.getToPoiId());
            }
        }
    }

    private void loadRouteAttractions() {
        routeToAttractions.clear();
        attractionToRoutes.clear();
        List<RouteAttraction> list = routeAttractionMapper.selectAll();
        if (list != null) {
            for (RouteAttraction ra : list) {
                if (ra == null || ra.getRouteId() == null || ra.getPoiId() == null) continue;
                routeToAttractions.computeIfAbsent(ra.getRouteId(), k -> new ArrayList<>())
                        .add(ra.getPoiId());
                attractionToRoutes.computeIfAbsent(ra.getPoiId(), k -> new ArrayList<>())
                        .add(ra.getRouteId());
            }
        }
    }

    private void loadProjects() {
        projects.clear();
        projectToRoute.clear();
        routeToProjects.clear();
        List<Project> list = projectMapper.selectAll();
        if (list != null) {
            for (Project p : list) {
                if (p == null || p.getId() == null) continue;
                projects.put(p.getId(), p);
                if (p.getRouteId() != null) {
                    projectToRoute.put(p.getId(), p.getRouteId());
                    routeToProjects.computeIfAbsent(p.getRouteId(), k -> new ArrayList<>())
                            .add(p.getId());
                }
            }
        }
    }

    private void loadUserTagPrefs() {
        userTagPrefs.clear();
        List<AccountTagPref> list = accountTagPrefMapper.selectAll();
        if (list != null) {
            for (AccountTagPref pref : list) {
                if (pref == null || pref.getAccountId() == null || pref.getTagId() == null) continue;
                Tag tag = tags.get(pref.getTagId());
                if (tag == null) continue;
                userTagPrefs.computeIfAbsent(pref.getAccountId(), k -> new ArrayList<>())
                        .add(tag.getName());
            }
        }
    }

    private void loadProjectMembers() {
        userProjects.clear();
        projectMembers.clear();
        List<ProjectMember> list = projectMemberMapper.selectAll();
        if (list != null) {
            for (ProjectMember pm : list) {
                if (pm == null || pm.getProjectId() == null || pm.getAccountId() == null) continue;
                userProjects.computeIfAbsent(pm.getAccountId(), k -> new ArrayList<>())
                        .add(pm.getProjectId());
                projectMembers.computeIfAbsent(pm.getProjectId(), k -> new ArrayList<>())
                        .add(pm.getAccountId());
            }
        }
    }

    private void loadAccountRegions() {
        accountRegion.clear();
        usersWithTagPrefs.clear();
        List<Account> list = accountMapper.selectAll();
        if (list != null) {
            for (Account a : list) {
                if (a != null && a.getId() != null && a.getRegionCode() != null && !a.getRegionCode().isBlank()) {
                    accountRegion.put(a.getId(), a.getRegionCode());
                }
            }
        }
        usersWithTagPrefs.addAll(userTagPrefs.keySet());
    }

    private void loadReviews() {
        userReviews.clear();
        projectAvgScore.clear();

        // 按项目聚合评分
        Map<Long, List<Integer>> projectScores = new LinkedHashMap<>();
        List<Review> list = reviewMapper.selectAll();
        if (list != null) {
            for (Review r : list) {
                if (r == null || r.getProjectId() == null || r.getOverallScore() == null) continue;
                projectScores.computeIfAbsent(r.getProjectId(), k -> new ArrayList<>())
                        .add(r.getOverallScore());

                if (r.getFromAccountId() != null) {
                    userReviews.computeIfAbsent(r.getFromAccountId(), k -> new LinkedHashMap<>())
                            .put(r.getProjectId(), r.getOverallScore());
                }
            }
        }

        for (Map.Entry<Long, List<Integer>> entry : projectScores.entrySet()) {
            double avg = entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0.0);
            projectAvgScore.put(entry.getKey(), avg);
        }
    }
}
