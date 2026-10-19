package com.viyangle.study_tour.graph;

import com.viyangle.study_tour.mapper.*;
import com.viyangle.study_tour.pojo.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeGraph} 的加载与查询行为测试。
 *
 * 知识图谱在启动时从 MySQL 装载全量节点和边，因此这里用 Mock 数据访问层，
 * 覆盖三个维度：装配结果正确、脏数据被过滤、依赖不可用时降级不抛异常。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeGraphTest {

    @Mock private AttractionMapper attractionMapper;
    @Mock private TagMapper tagMapper;
    @Mock private AttractionTagMapper attractionTagMapper;
    @Mock private AttractionAdjacencyMapper adjacencyMapper;
    @Mock private RouteAttractionMapper routeAttractionMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private AccountTagPrefMapper accountTagPrefMapper;
    @Mock private ReviewMapper reviewMapper;
    @Mock private AccountMapper accountMapper;

    @InjectMocks
    private KnowledgeGraph graph;

    // ==================== 测试数据构造 ====================

    private static Attraction attraction(String poiId, String adcode) {
        Attraction a = new Attraction();
        a.setPoiId(poiId);
        a.setName("景点-" + poiId);
        a.setAdcode(adcode);
        return a;
    }

    private static Tag tag(long id, String name) {
        Tag t = new Tag();
        t.setId(id);
        t.setName(name);
        return t;
    }

    private static AttractionTag attractionTag(String poiId, long tagId) {
        AttractionTag at = new AttractionTag();
        at.setPoiId(poiId);
        at.setTagId(tagId);
        return at;
    }

    private static AttractionAdjacency edge(String from, String to, String type, Double similarity) {
        AttractionAdjacency adj = new AttractionAdjacency();
        adj.setFromPoiId(from);
        adj.setToPoiId(to);
        adj.setRelationType(type);
        adj.setSimilarityScore(similarity);
        return adj;
    }

    private static RouteAttraction routeAttraction(long routeId, String poiId) {
        RouteAttraction ra = new RouteAttraction();
        ra.setRouteId(routeId);
        ra.setPoiId(poiId);
        return ra;
    }

    private static Project project(long id, Long routeId) {
        Project p = new Project();
        p.setId(id);
        p.setRouteId(routeId);
        p.setTitle("项目-" + id);
        return p;
    }

    private static ProjectMember member(long projectId, long accountId) {
        ProjectMember pm = new ProjectMember();
        pm.setProjectId(projectId);
        pm.setAccountId(accountId);
        return pm;
    }

    private static Review review(Long projectId, long fromAccountId, int score) {
        Review r = new Review();
        r.setProjectId(projectId);
        r.setFromAccountId(fromAccountId);
        r.setOverallScore(score);
        return r;
    }

    private static Account account(long id, String regionCode) {
        Account a = new Account();
        a.setId(id);
        a.setRegionCode(regionCode);
        return a;
    }

    /** 装配一份完整但规模极小的图数据。 */
    private void stubFullGraph() {
        when(attractionMapper.selectAll()).thenReturn(List.of(
                attraction("P1", "320102"),
                attraction("P2", "320102"),
                attraction("P3", "320105")));
        when(tagMapper.selectAll()).thenReturn(List.of(
                tag(1L, "历史人文"),
                tag(2L, "博物馆研学")));
        when(attractionTagMapper.selectAll()).thenReturn(List.of(
                attractionTag("P1", 1L),
                attractionTag("P1", 2L),
                attractionTag("P2", 1L)));
        when(adjacencyMapper.selectAll()).thenReturn(List.of(
                edge("P1", "P2", "GEOGRAPHIC", null),
                edge("P1", "P3", "THEMATIC", 0.9),
                edge("P2", "P2", "GEOGRAPHIC", null)));
        when(routeAttractionMapper.selectAll()).thenReturn(List.of(
                routeAttraction(100L, "P1"),
                routeAttraction(100L, "P2")));
        when(projectMapper.selectAll()).thenReturn(List.of(
                project(200L, 100L),
                project(201L, null)));
        when(accountTagPrefMapper.selectAll()).thenReturn(List.of(pref(7L, 1L)));
        when(projectMemberMapper.selectAll()).thenReturn(List.of(
                member(200L, 7L),
                member(200L, 8L)));
        when(reviewMapper.selectAll()).thenReturn(List.of(
                review(200L, 7L, 4),
                review(200L, 8L, 5)));
        when(accountMapper.selectAll()).thenReturn(List.of(
                account(7L, "320102"),
                account(8L, "   ")));
    }

    private static AccountTagPref pref(long accountId, long tagId) {
        AccountTagPref p = new AccountTagPref();
        p.setAccountId(accountId);
        p.setTagId(tagId);
        return p;
    }

    /** 所有依赖返回空集合，用于验证各查询方法的兜底默认值。 */
    private void stubEmptyGraph() {
        when(attractionMapper.selectAll()).thenReturn(List.of());
        when(tagMapper.selectAll()).thenReturn(List.of());
        when(attractionTagMapper.selectAll()).thenReturn(List.of());
        when(adjacencyMapper.selectAll()).thenReturn(List.of());
        when(routeAttractionMapper.selectAll()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(accountTagPrefMapper.selectAll()).thenReturn(List.of());
        when(projectMemberMapper.selectAll()).thenReturn(List.of());
        when(reviewMapper.selectAll()).thenReturn(List.of());
        when(accountMapper.selectAll()).thenReturn(List.of());
    }

    // ==================== 装配正确性 ====================

    @Test
    @DisplayName("reload 从各数据源装配节点、边与派生指标")
    void reloadAssemblesNodesAndEdges() {
        stubFullGraph();

        graph.reload();

        assertTrue(graph.isLoaded(), "reload 完成后应标记为已加载");
        assertEquals(Set.of("P1", "P2", "P3"), graph.getAllAttractionPoiIds());
        assertEquals(Set.of(200L, 201L), graph.getAllProjectIds());

        // 标签双向索引
        assertEquals(List.of("历史人文", "博物馆研学"), graph.getAttractionTags("P1"));
        assertEquals(List.of("P1", "P2"), graph.getAttractionsByTag("历史人文"));
        assertEquals(Set.of("P1", "P2"), graph.getAttractionsByTags(List.of("历史人文", "博物馆研学")),
                "多标签查询应按景点去重");

        // 地理边与语义边分流
        assertEquals(List.of("P2"), graph.getNeighbors("P1"));
        assertTrue(graph.getNeighbors("P2").isEmpty(), "自引用占位记录应被跳过");
        assertEquals(Map.of("P3", 0.9), graph.getSemanticNeighbors("P1"));

        // 路线与项目
        assertEquals(List.of("P1", "P2"), graph.getRouteAttractions(100L));
        assertEquals(List.of(100L), graph.getRoutesByAttraction("P1"));
        assertEquals(100L, graph.getProjectRoute(200L));
        assertNull(graph.getProjectRoute(201L), "无路线项目不应产生映射");
        assertEquals(List.of(200L), graph.getProjectsByRoute(100L));

        // 用户维度
        assertEquals(List.of("历史人文"), graph.getUserTagPrefs(7L));
        assertEquals(List.of(200L), graph.getUserProjects(7L));
        assertEquals(List.of(7L, 8L), graph.getProjectMembers(200L));
        assertEquals(2, graph.getProjectMemberCount(200L));
        assertEquals(List.of(7L), graph.getUsersWithTagPrefs());

        // 评分聚合：4 与 5 的平均值
        assertEquals(4.5, graph.getProjectAvgScore(200L), 1e-9);

        // 地区：空白 adcode / regionCode 不入索引
        assertEquals("320102", graph.getAttractionRegion("P1"));
        assertEquals("320102", graph.getUserRegion(7L));
        assertNull(graph.getUserRegion(8L), "空白地区码应被忽略");

        // 节点实体
        assertEquals("景点-P1", graph.getAttraction("P1").getName());
        assertEquals("项目-200", graph.getProject(200L).getTitle());
    }

    @Test
    @DisplayName("expandNeighbors 按跳数扩展、去重且能处理环路")
    void expandNeighborsRespectsHopsAndDeduplicates() {
        when(attractionMapper.selectAll()).thenReturn(List.of(
                attraction("P1", null),
                attraction("P2", null),
                attraction("P3", null)));
        when(tagMapper.selectAll()).thenReturn(List.of());
        when(attractionTagMapper.selectAll()).thenReturn(List.of());
        // P1 → P2 → P3 → P1 构成环路
        when(adjacencyMapper.selectAll()).thenReturn(List.of(
                edge("P1", "P2", "GEOGRAPHIC", null),
                edge("P2", "P3", "GEOGRAPHIC", null),
                edge("P3", "P1", "GEOGRAPHIC", null)));
        when(routeAttractionMapper.selectAll()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(accountTagPrefMapper.selectAll()).thenReturn(List.of());
        when(projectMemberMapper.selectAll()).thenReturn(List.of());
        when(reviewMapper.selectAll()).thenReturn(List.of());
        when(accountMapper.selectAll()).thenReturn(List.of());
        graph.reload();

        assertEquals(Set.of("P1", "P2"), graph.expandNeighbors(List.of("P1"), 1));
        assertEquals(Set.of("P1", "P2", "P3"), graph.expandNeighbors(List.of("P1"), 2));
        assertEquals(Set.of("P1", "P2", "P3"), graph.expandNeighbors(List.of("P1"), 10),
                "环路下多跳扩展应终止并去重");
        assertEquals(Set.of("P1"), graph.expandNeighbors(List.of("P1"), 0), "0 跳只返回种子节点");
    }

    // ==================== 脏数据与降级 ====================

    @Test
    @DisplayName("reload 过滤空记录、未知标签与外键为空的边")
    void reloadIgnoresInvalidRecords() {
        when(attractionMapper.selectAll()).thenReturn(Arrays.asList(
                attraction("P1", null), null));
        when(tagMapper.selectAll()).thenReturn(List.of(tag(1L, "历史人文")));
        when(attractionTagMapper.selectAll()).thenReturn(Arrays.asList(
                attractionTag("P1", 1L),
                attractionTag("P1", 99L),      // 引用了不存在的标签
                attractionTag(null, 1L),       // 缺少景点
                null));
        when(adjacencyMapper.selectAll()).thenReturn(Arrays.asList(
                edge("P1", "P1", "GEOGRAPHIC", null),  // 自引用
                edge(null, "P2", "GEOGRAPHIC", null),  // 缺少起点
                null));
        when(routeAttractionMapper.selectAll()).thenReturn(Arrays.asList(
                routeAttraction(100L, "P1"), null));
        when(projectMapper.selectAll()).thenReturn(Arrays.asList(project(200L, null), null));
        when(accountTagPrefMapper.selectAll()).thenReturn(Arrays.asList(pref(7L, 99L), null));
        when(projectMemberMapper.selectAll()).thenReturn(Arrays.asList(member(200L, 7L), null));
        when(reviewMapper.selectAll()).thenReturn(Arrays.asList(review(null, 7L, 5), null));
        when(accountMapper.selectAll()).thenReturn(Arrays.asList(account(7L, null), null));

        graph.reload();

        assertEquals(Set.of("P1"), graph.getAllAttractionPoiIds(), "null 记录不应进入图");
        assertTrue(graph.getNeighbors("P1").isEmpty(), "自引用与缺字段的边应被丢弃");
        assertEquals(List.of("历史人文"), graph.getAttractionTags("P1"), "仅保留能解析到标签名的关联");
        assertEquals(Set.of(200L), graph.getAllProjectIds());
        assertTrue(graph.getUserTagPrefs(7L).isEmpty(), "指向未知标签的偏好应被跳过");
        assertTrue(graph.getUsersWithTagPrefs().isEmpty());
        assertNull(graph.getProjectRoute(200L), "无路线项目不建立映射");
        assertEquals(0.0, graph.getProjectAvgScore(200L), 1e-9, "缺项目 ID 的评价不参与聚合");
        assertNull(graph.getAttractionRegion("P1"));
    }

    @Test
    @DisplayName("数据源返回 null 时 reload 仍成功并给出空图")
    void reloadToleratesNullMapperResults() {
        when(attractionMapper.selectAll()).thenReturn(null);
        when(tagMapper.selectAll()).thenReturn(null);
        when(attractionTagMapper.selectAll()).thenReturn(null);
        when(adjacencyMapper.selectAll()).thenReturn(null);
        when(routeAttractionMapper.selectAll()).thenReturn(null);
        when(projectMapper.selectAll()).thenReturn(null);
        when(accountTagPrefMapper.selectAll()).thenReturn(null);
        when(projectMemberMapper.selectAll()).thenReturn(null);
        when(reviewMapper.selectAll()).thenReturn(null);
        when(accountMapper.selectAll()).thenReturn(null);

        assertDoesNotThrow(() -> graph.reload());
        assertTrue(graph.isLoaded());
        assertTrue(graph.getAllAttractionPoiIds().isEmpty());
    }

    @Test
    @DisplayName("init 在数据源异常时降级：不抛异常且不标记为已加载")
    void initSwallowsLoadFailure() {
        when(attractionMapper.selectAll()).thenThrow(new IllegalStateException("数据库不可用"));

        assertDoesNotThrow(() -> graph.init(),
                "图谱初始化失败不应阻断应用启动，推荐功能降级即可");
        assertFalse(graph.isLoaded());
    }

    // ==================== 查询兜底 ====================

    @Test
    @DisplayName("查询不存在的键时返回空集合 / 0 / null 而非抛异常")
    void queriesReturnSafeDefaultsForUnknownKeys() {
        stubEmptyGraph();
        graph.reload();

        assertTrue(graph.getAttractionTags("NOPE").isEmpty());
        assertTrue(graph.getAttractionsByTag("NOPE").isEmpty());
        assertTrue(graph.getAttractionsByTags(List.of("NOPE")).isEmpty());
        assertTrue(graph.getNeighbors("NOPE").isEmpty());
        assertTrue(graph.getSemanticNeighbors("NOPE").isEmpty());
        assertTrue(graph.expandNeighbors(List.of("NOPE"), 3).contains("NOPE"));
        assertTrue(graph.getRouteAttractions(1L).isEmpty());
        assertTrue(graph.getRoutesByAttraction("NOPE").isEmpty());
        assertTrue(graph.getProjectsByRoute(1L).isEmpty());
        assertTrue(graph.getUserTagPrefs(1L).isEmpty());
        assertTrue(graph.getUserProjects(1L).isEmpty());
        assertTrue(graph.getProjectMembers(1L).isEmpty());
        assertEquals(0, graph.getProjectMemberCount(1L));
        assertEquals(0.0, graph.getProjectAvgScore(1L), 1e-9);
        assertNull(graph.getAttraction("NOPE"));
        assertNull(graph.getProject(1L));
        assertNull(graph.getProjectRoute(1L));
        assertNull(graph.getAttractionRegion("NOPE"));
        assertNull(graph.getUserRegion(1L));
        assertTrue(graph.getUsersWithTagPrefs().isEmpty());
    }

    // ==================== 个性化 PageRank ====================

    @Test
    @DisplayName("personalizedPageRank 对空种子或空图返回空结果")
    void personalizedPageRankReturnsEmptyForEmptyInput() {
        assertTrue(graph.personalizedPageRank(null, 10, 0.85).isEmpty());
        assertTrue(graph.personalizedPageRank(List.of(), 10, 0.85).isEmpty());

        stubEmptyGraph();
        graph.reload();
        assertTrue(graph.personalizedPageRank(List.of("P1"), 10, 0.85).isEmpty(),
                "图中无景点时应直接返回空结果");
    }

    @Test
    @DisplayName("personalizedPageRank 只沿边传播权重，孤立节点保持 0")
    void personalizedPageRankPropagatesAlongEdges() {
        when(attractionMapper.selectAll()).thenReturn(List.of(
                attraction("P1", null),
                attraction("P2", null),
                attraction("P3", null)));
        when(tagMapper.selectAll()).thenReturn(List.of(tag(1L, "历史人文")));
        when(attractionTagMapper.selectAll()).thenReturn(List.of(attractionTag("P1", 1L)));
        when(adjacencyMapper.selectAll()).thenReturn(List.of(
                edge("P1", "P2", "GEOGRAPHIC", null)));
        when(routeAttractionMapper.selectAll()).thenReturn(List.of());
        when(projectMapper.selectAll()).thenReturn(List.of());
        when(accountTagPrefMapper.selectAll()).thenReturn(List.of());
        when(projectMemberMapper.selectAll()).thenReturn(List.of());
        when(reviewMapper.selectAll()).thenReturn(List.of());
        when(accountMapper.selectAll()).thenReturn(List.of());
        graph.reload();

        Map<String, Double> scores = graph.personalizedPageRank(List.of("P1"), 50, 0.85);

        assertEquals(Set.of("P1", "P2", "P3"), scores.keySet(), "应为每个景点给出分数");
        assertTrue(scores.get("P1") > 0, "种子节点应持有权重");
        assertTrue(scores.get("P2") > 0, "相邻节点应通过边获得权重");
        assertEquals(0.0, scores.get("P3"), 1e-12, "无入边的孤立景点不应获得权重");
    }
}
