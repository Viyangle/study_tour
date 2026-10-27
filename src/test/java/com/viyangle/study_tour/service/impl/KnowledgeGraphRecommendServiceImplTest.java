package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.graph.KnowledgeGraph;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.Project;
import com.viyangle.study_tour.pojo.RecommendedProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeGraphRecommendServiceImpl} 的行为测试。
 *
 * <p>推荐链路是整个系统的核心业务，且只依赖内存中的 {@link KnowledgeGraph}，
 * 因此用 Mock 图谱即可在无 MySQL / Redis 的环境下覆盖：打分权重、状态过滤、
 * 冷启动兜底、MMR 多样性、推荐理由生成与 AI 候选召回。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeGraphRecommendServiceImplTest {

    @Mock
    private KnowledgeGraph graph;

    @InjectMocks
    private KnowledgeGraphRecommendServiceImpl service;

    // ==================== 测试数据构造 ====================

    private static Project project(long id, String status, String region, LocalDateTime createdAt) {
        Project p = new Project();
        p.setId(id);
        p.setStatus(status);
        p.setRegionAdcode(region);
        p.setCreatedAt(createdAt);
        p.setTitle("项目-" + id);
        return p;
    }

    private static Project openProject(long id) {
        return project(id, "OPEN", null, LocalDateTime.now());
    }

    private static Attraction attraction(String poiId, String name, String location) {
        Attraction a = new Attraction();
        a.setPoiId(poiId);
        a.setName(name);
        a.setLocation(location);
        return a;
    }

    private void graphLoaded() {
        when(graph.isLoaded()).thenReturn(true);
    }

    private void projectIds(Long... ids) {
        when(graph.getAllProjectIds()).thenReturn(new LinkedHashSet<>(List.of(ids)));
    }

    private void projectPrefs(long accountId, String region, String... tags) {
        when(graph.getUserTagPrefs(accountId)).thenReturn(List.of(tags));
        when(graph.getUserRegion(accountId)).thenReturn(region);
        when(graph.getUserProjects(accountId)).thenReturn(List.of());
        when(graph.getUsersWithTagPrefs()).thenReturn(List.of(accountId));
    }

    private void registerProject(Project project, double avgScore) {
        when(graph.getProject(project.getId())).thenReturn(project);
        when(graph.getProjectAvgScore(project.getId())).thenReturn(avgScore);
    }

    private void routeOf(long projectId, long routeId, String... poiIds) {
        when(graph.getProjectRoute(projectId)).thenReturn(routeId);
        when(graph.getRouteAttractions(routeId)).thenReturn(List.of(poiIds));
    }

    private static List<Long> idsOf(List<Project> projects) {
        List<Long> ids = new ArrayList<>();
        for (Project p : projects) {
            ids.add(p.getId());
        }
        return ids;
    }

    private static boolean hasReasonStartingWith(List<String> reasons, String prefix) {
        return reasons.stream().anyMatch(r -> r.startsWith(prefix));
    }

    /** 按传入的 poiId 返回景点，保证断言能区分候选顺序。 */
    private void stubAttractionsByPoi() {
        when(graph.getAttraction(anyString()))
                .thenAnswer(invocation -> attraction(invocation.getArgument(0), "景点", "118.1,32.1"));
    }

    // ==================== 首页项目推荐 ====================

    @Nested
    @DisplayName("首页项目推荐 recommendProjects")
    class RecommendProjects {

        @Test
        @DisplayName("图谱未加载时返回空列表，避免空指针")
        void returnsEmptyWhenGraphNotLoaded() {
            when(graph.isLoaded()).thenReturn(false);

            assertTrue(service.recommendProjects(1L, 10).isEmpty());
            verify(graph, never()).getAllProjectIds();
        }

        @Test
        @DisplayName("用户无标签偏好时走冷启动：按参与人数与评分排序")
        void coldStartRanksByPopularityAndRating() {
            graphLoaded();
            projectIds(1L, 2L, 3L);
            Project hot = openProject(1);
            Project rated = openProject(2);
            Project plain = openProject(3);
            registerProject(hot, 5.0);
            registerProject(rated, 0.0);
            registerProject(plain, 2.0);
            when(graph.getProjectMemberCount(1L)).thenReturn(20);
            when(graph.getProjectMemberCount(2L)).thenReturn(10);
            when(graph.getProjectMemberCount(3L)).thenReturn(0);
            when(graph.getUserTagPrefs(1L)).thenReturn(List.of());

            List<Project> result = service.recommendProjects(1L, 10);

            // hot = 30 + 40 = 70；rated = 15 + 20(无评分兜底) = 35；plain = 16
            assertEquals(List.of(1L, 2L, 3L), idsOf(result));
        }

        @Test
        @DisplayName("地区匹配按同区 > 同市 > 同省递减，完全不匹配的项目不进入推荐")
        void regionMatchIsSoftConstraintWithGrading() {
            graphLoaded();
            projectIds(1L, 2L, 3L, 4L);
            registerProject(project(1, "OPEN", "320102", LocalDateTime.now()), 0.0);
            registerProject(project(2, "OPEN", "320100", LocalDateTime.now()), 0.0);
            registerProject(project(3, "OPEN", "321000", LocalDateTime.now()), 0.0);
            registerProject(project(4, "OPEN", "110000", LocalDateTime.now()), 0.0);
            projectPrefs(1L, "320102", "历史人文");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            List<Project> result = service.recommendProjects(1L, 10);

            assertEquals(List.of(1L, 2L, 3L), idsOf(result));
        }

        @Test
        @DisplayName("相似用户参与过的项目获得协同过滤加分并排到前面")
        void collaborativeFilteringBoostsProject() {
            graphLoaded();
            projectIds(1L, 2L);
            registerProject(openProject(1), 5.0);
            registerProject(openProject(2), 4.0);
            projectPrefs(1L, null, "历史人文");
            when(graph.getUsersWithTagPrefs()).thenReturn(List.of(1L, 2L));
            when(graph.getUserTagPrefs(2L)).thenReturn(List.of("历史人文"));
            when(graph.getUserProjects(2L)).thenReturn(List.of(2L));
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            List<Project> result = service.recommendProjects(1L, 10);

            // p1 = 10(评分)；p2 = 8(评分) + 20(协同) = 28
            assertEquals(List.of(2L, 1L), idsOf(result));
        }

        @Test
        @DisplayName("协同过滤只保留相似用户的项目，且排除自己已参与的项目")
        void collaborativeFilteringExcludesOwnProjects() {
            graphLoaded();
            projectIds(1L, 2L, 3L);
            registerProject(openProject(1), 0.0);
            registerProject(openProject(2), 0.0);
            registerProject(openProject(3), 5.0);
            projectPrefs(1L, null, "A");
            when(graph.getUserProjects(1L)).thenReturn(List.of(1L));
            when(graph.getUsersWithTagPrefs()).thenReturn(List.of(2L));
            when(graph.getUserTagPrefs(2L)).thenReturn(List.of("A", "B"));
            when(graph.getUserProjects(2L)).thenReturn(List.of(1L, 2L));
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            List<Project> result = service.recommendProjects(1L, 10);

            // p1 是自己已参与的项目，没有协同加分且无其它得分，必须被排除；
            // p2 得到 20 分协同加分，p3 只有 10 分评分
            assertEquals(List.of(2L, 3L), idsOf(result));
        }

        @Test
        @DisplayName("PageRank 图传播得分权重高于评分，可让图谱命中项目反超")
        void pageRankScoreCanOutrankReviewScore() {
            graphLoaded();
            projectIds(1L, 2L);
            registerProject(openProject(1), 0.0);
            registerProject(openProject(2), 5.0);
            routeOf(1L, 11L, "a1");
            projectPrefs(1L, null, "历史人文");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of("a1"));
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble()))
                    .thenReturn(Map.of("a1", 0.5));

            List<Project> result = service.recommendProjects(1L, 10);

            // p1 = 45 * min(1, 0.5 * 1 * 10) = 45；p2 = 10
            assertEquals(List.of(1L, 2L), idsOf(result));
        }

        @Test
        @DisplayName("评分按 5 分制线性折算到 10 分权重")
        void reviewScoreIsNormalized() {
            graphLoaded();
            projectIds(1L, 2L, 3L);
            registerProject(openProject(1), 5.0);
            registerProject(openProject(2), 2.5);
            registerProject(openProject(3), 0.0);
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            List<Project> result = service.recommendProjects(1L, 10);

            // 无评分项目得分为 0 被过滤；2.5 分项目保留但排在 5 分项目之后
            assertEquals(List.of(1L, 2L), idsOf(result));
        }

        @Test
        @DisplayName("只推荐 OPEN / MATCHING 状态的项目")
        void onlyOpenOrMatchingProjectsAreRecommended() {
            graphLoaded();
            projectIds(1L, 2L, 3L, 4L, 5L);
            registerProject(project(1, "OPEN", null, LocalDateTime.now()), 4.0);
            registerProject(project(2, "MATCHING", null, LocalDateTime.now()), 4.0);
            registerProject(project(3, "CONFIRMED", null, LocalDateTime.now()), 4.0);
            registerProject(project(4, "DONE", null, LocalDateTime.now()), 4.0);
            registerProject(project(5, "CANCELLED", null, LocalDateTime.now()), 4.0);
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            List<Project> result = service.recommendProjects(1L, 10);

            assertEquals(List.of(1L, 2L), idsOf(result));
        }

        @Test
        @DisplayName("时间衰减让新建项目优先于创建已久的同分项目")
        void newerProjectsRankHigherByTimeDecay() {
            graphLoaded();
            projectIds(1L, 2L);
            LocalDateTime now = LocalDateTime.now();
            registerProject(project(1, "OPEN", null, now), 5.0);
            registerProject(project(2, "OPEN", null, now.minusDays(100)), 5.0);
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            List<Project> result = service.recommendProjects(1L, 10);

            // 100 天衰减到 1/(1+1)=0.5，旧项目排后
            assertEquals(List.of(1L, 2L), idsOf(result));
        }

        @Test
        @DisplayName("候选多于 limit 时用 MMR 提升多样性：标签相近的高分项目让位给异构项目")
        void mmrPrefersDiverseProjectOverSimilarHigherScore() {
            graphLoaded();
            projectIds(1L, 2L, 3L);
            registerProject(openProject(1), 5.0);
            registerProject(openProject(2), 4.5);
            registerProject(openProject(3), 4.0);
            routeOf(1L, 11L, "a1");
            routeOf(2L, 12L, "a2");
            routeOf(3L, 13L, "a3");
            when(graph.getAttractionTags("a1")).thenReturn(List.of("X"));
            when(graph.getAttractionTags("a2")).thenReturn(List.of("X"));
            when(graph.getAttractionTags("a3")).thenReturn(List.of("Y"));
            projectPrefs(1L, null, "X");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            List<Project> result = service.recommendProjects(1L, 2);

            // 先选 p1；p2 与 p1 标签完全相同被 MMR 惩罚，p3 虽分子更低但更互补
            assertEquals(List.of(1L, 3L), idsOf(result));
        }

        @Test
        @DisplayName("limit 非正数时回落到默认 10 条")
        void nonPositiveLimitFallsBackToDefault() {
            graphLoaded();
            Long[] ids = new Long[15];
            for (int i = 0; i < 15; i++) {
                ids[i] = (long) (i + 1);
                registerProject(openProject(i + 1L), 5.0);
            }
            when(graph.getAllProjectIds()).thenReturn(new LinkedHashSet<>(List.of(ids)));
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            assertEquals(10, service.recommendProjects(1L, 0).size());
            assertEquals(10, service.recommendProjects(1L, -5).size());
        }

        @Test
        @DisplayName("图谱中缺失的项目实体被跳过，不影响其余推荐")
        void missingProjectEntityIsSkipped() {
            graphLoaded();
            projectIds(1L, 2L);
            when(graph.getProject(1L)).thenReturn(openProject(1));
            when(graph.getProject(2L)).thenReturn(null);
            when(graph.getProject(1L)).thenReturn(openProject(1));
            when(graph.getProjectAvgScore(1L)).thenReturn(5.0);
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            assertEquals(List.of(1L), idsOf(service.recommendProjects(1L, 10)));
        }

        @Test
        @DisplayName("无任何正得分的候选不会返回，避免把无关项目推给用户")
        void zeroScoreProjectsAreExcluded() {
            graphLoaded();
            projectIds(1L);
            registerProject(openProject(1), 0.0);
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            assertTrue(service.recommendProjects(1L, 10).isEmpty());
        }

        @Test
        @DisplayName("accountId 为空时按游客处理，不查询用户偏好")
        void nullAccountIdTreatedAsGuest() {
            graphLoaded();
            projectIds(1L);
            registerProject(openProject(1), 5.0);
            when(graph.getProjectMemberCount(1L)).thenReturn(20);

            List<Project> result = service.recommendProjects(null, 10);

            assertEquals(List.of(1L), idsOf(result));
            verify(graph, never()).getUserTagPrefs(null);
        }
    }

    // ==================== 带解释的推荐 ====================

    @Nested
    @DisplayName("带解释的推荐 recommendWithExplanation")
    class RecommendWithExplanation {

        @Test
        @DisplayName("图谱未加载时返回空列表")
        void returnsEmptyWhenGraphNotLoaded() {
            when(graph.isLoaded()).thenReturn(false);

            assertTrue(service.recommendWithExplanation(1L, 10).isEmpty());
        }

        @Test
        @DisplayName("关键词匹配标题，忽略大小写与首尾空格，并排除不匹配项目")
        void keywordMatchesTitleIgnoringCaseAndSpaces() {
            graphLoaded();
            projectIds(1L, 2L);
            Project nanjing = openProject(1);
            nanjing.setTitle("Nanjing History Tour");
            Project suzhou = openProject(2);
            suzhou.setTitle("Suzhou Garden Tour");
            registerProject(nanjing, 4.0);
            registerProject(suzhou, 4.0);
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            List<RecommendedProject> result = service.recommendWithExplanation(1L, "  NANJING ", 10);

            assertEquals(1, result.size());
            assertEquals(1L, result.get(0).getProject().getId());
        }

        @Test
        @DisplayName("关键词可匹配标签、状态与出发点")
        void keywordMatchesTagStatusAndStartPoint() {
            graphLoaded();
            projectIds(1L, 2L, 3L);
            Project tagged = openProject(1);
            tagged.setTag("博物馆研学");
            Project matching = project(2, "MATCHING", null, LocalDateTime.now());
            Project located = openProject(3);
            located.setStartPoint("南京南站北广场");
            registerProject(tagged, 4.0);
            registerProject(matching, 4.0);
            registerProject(located, 4.0);
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            assertEquals(1, service.recommendWithExplanation(1L, "博物馆", 10).size());
            assertEquals(1, service.recommendWithExplanation(1L, "matching", 10).size());
            assertEquals(1, service.recommendWithExplanation(1L, "南京南站", 10).size());
        }

        @Test
        @DisplayName("关键词可匹配领队要求与参与者要求")
        void keywordMatchesRequirements() {
            graphLoaded();
            projectIds(1L);
            Project p = openProject(1);
            p.setLeaderRequirements("需要有博物馆讲解经验");
            p.setParticipantRequirements("适合10岁以上学生");
            registerProject(p, 4.0);
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            assertEquals(1, service.recommendWithExplanation(1L, "讲解经验", 10).size());
            assertEquals(1, service.recommendWithExplanation(1L, "10岁", 10).size());
        }

        @Test
        @DisplayName("空关键词等同于不筛选，返回完整推荐列表")
        void blankKeywordEqualsNoFilter() {
            graphLoaded();
            projectIds(1L, 2L);
            registerProject(openProject(1), 4.0);
            registerProject(openProject(2), 4.0);
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            assertEquals(2, service.recommendWithExplanation(1L, "   ", 10).size());
        }

        @Test
        @DisplayName("标签命中时给出“因为你偏好”理由并点名景点")
        void tagMatchProducesReasonWithAttractionName() {
            graphLoaded();
            projectIds(1L);
            registerProject(openProject(1), 4.5);
            routeOf(1L, 11L, "a1");
            when(graph.getAttractionTags("a1")).thenReturn(List.of("历史人文"));
            when(graph.getAttraction("a1")).thenReturn(attraction("a1", "故宫", "116.39,39.91"));
            projectPrefs(1L, null, "历史人文");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of("a1"));
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            List<String> reasons = service.recommendWithExplanation(1L, 10).get(0).getReasons();

            assertTrue(hasReasonStartingWith(reasons, "因为你偏好[历史人文]"), reasons.toString());
            assertTrue(reasons.stream().anyMatch(r -> r.contains("故宫")), reasons.toString());
        }

        @Test
        @DisplayName("相似用户参与过时给出协同过滤理由")
        void collaborativeReasonIsExplained() {
            graphLoaded();
            projectIds(1L);
            registerProject(openProject(1), 4.0);
            projectPrefs(1L, null, "A");
            when(graph.getUsersWithTagPrefs()).thenReturn(List.of(2L));
            when(graph.getUserTagPrefs(2L)).thenReturn(List.of("A"));
            when(graph.getUserProjects(2L)).thenReturn(List.of(1L));
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            List<String> reasons = service.recommendWithExplanation(1L, 10).get(0).getReasons();

            assertTrue(reasons.contains("与你兴趣相似的人也参与了该项目"), reasons.toString());
        }

        @Test
        @DisplayName("评分不低于 4 分时给出评分理由")
        void highRatingProducesReason() {
            graphLoaded();
            projectIds(1L);
            registerProject(openProject(1), 4.5);
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            List<String> reasons = service.recommendWithExplanation(1L, 10).get(0).getReasons();

            assertTrue(hasReasonStartingWith(reasons, "该项目口碑很好"), reasons.toString());
        }

        @Test
        @DisplayName("同省项目给出出行便利理由")
        void sameProvinceProducesReason() {
            graphLoaded();
            projectIds(1L);
            registerProject(project(1, "OPEN", "320100", LocalDateTime.now()), 4.0);
            projectPrefs(1L, "320102", "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            List<String> reasons = service.recommendWithExplanation(1L, 10).get(0).getReasons();

            assertTrue(reasons.contains("项目就在你所在省份，出行方便"), reasons.toString());
        }

        @Test
        @DisplayName("路线景点邻近用户兴趣景点时给出相邻扩展理由")
        void neighborExpansionProducesReason() {
            graphLoaded();
            projectIds(1L);
            registerProject(openProject(1), 4.0);
            routeOf(1L, 11L, "a1");
            when(graph.getNeighbors("a1")).thenReturn(List.of("n1"));
            when(graph.getAttraction("n1")).thenReturn(attraction("n1", "南京博物院", "118.79,32.04"));
            when(graph.getAttraction("a1")).thenReturn(attraction("a1", "中山陵", "118.85,32.06"));
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of("n1"));
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            List<String> reasons = service.recommendWithExplanation(1L, 10).get(0).getReasons();

            assertTrue(reasons.contains("路线上的中山陵靠近你感兴趣的南京博物院"), reasons.toString());
        }

        @Test
        @DisplayName("无任何命中理由时回落到通用文案")
        void fallsBackToGenericReason() {
            graphLoaded();
            projectIds(1L);
            registerProject(openProject(1), 0.0);
            routeOf(1L, 11L, "a1");
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble()))
                    .thenReturn(Map.of("a1", 0.5));

            List<String> reasons = service.recommendWithExplanation(1L, 10).get(0).getReasons();

            assertEquals(List.of("热门项目，值得一试"), reasons);
        }

        @Test
        @DisplayName("返回条数受 limit 限制")
        void respectsLimit() {
            graphLoaded();
            projectIds(1L, 2L, 3L, 4L, 5L);
            for (long id = 1; id <= 5; id++) {
                registerProject(openProject(id), 5.0);
            }
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            assertEquals(2, service.recommendWithExplanation(1L, 2).size());
        }

        @Test
        @DisplayName("推荐结果同时携带项目与理由，满足前端卡片展示")
        void resultCarriesProjectAndReasons() {
            graphLoaded();
            projectIds(1L);
            registerProject(openProject(1), 4.0);
            projectPrefs(1L, null, "A");
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(Set.of());
            when(graph.personalizedPageRank(anyCollection(), anyInt(), anyDouble())).thenReturn(Map.of());

            RecommendedProject r = service.recommendWithExplanation(1L, 10).get(0);

            assertEquals(1L, r.getProject().getId());
            assertFalse(r.getReasons().isEmpty());
        }
    }

    // ==================== AI 路线候选召回 ====================

    @Nested
    @DisplayName("AI 路线候选召回 retrieveCandidatesByGraph")
    class RetrieveCandidates {

        @Test
        @DisplayName("图谱未加载时返回空候选")
        void returnsEmptyWhenGraphNotLoaded() {
            when(graph.isLoaded()).thenReturn(false);

            assertTrue(service.retrieveCandidatesByGraph(List.of("历史人文"), "320102", 10).isEmpty());
        }

        @Test
        @DisplayName("无标签偏好时用全量景点作为种子")
        void usesAllAttractionsWhenNoTags() {
            graphLoaded();
            when(graph.getAllAttractionPoiIds()).thenReturn(new LinkedHashSet<>(List.of("a1", "a2")));
            when(graph.expandNeighbors(anyCollection(), eq(2)))
                    .thenReturn(new LinkedHashSet<>(List.of("a1", "a2")));
            when(graph.getAttraction(anyString()))
                    .thenReturn(attraction("a1", "景点", "118.1,32.1"));

            assertEquals(2, service.retrieveCandidatesByGraph(List.of(), null, 10).size());
            verify(graph, never()).getAttractionsByTags(anyCollection());
        }

        @Test
        @DisplayName("同区与同市候选排在其它地区之前")
        void sameCityCandidatesRankFirst() {
            graphLoaded();
            when(graph.getAttractionsByTags(anyCollection()))
                    .thenReturn(new LinkedHashSet<>(List.of("p1", "p2", "p3")));
            when(graph.getAttractionRegion("p1")).thenReturn("320102");
            when(graph.getAttractionRegion("p2")).thenReturn("320100");
            when(graph.getAttractionRegion("p3")).thenReturn("110000");
            when(graph.expandNeighbors(anyCollection(), eq(2)))
                    .thenReturn(new LinkedHashSet<>(List.of("p1", "p2", "p3")));
            stubAttractionsByPoi();

            List<Attraction> result = service.retrieveCandidatesByGraph(List.of("历史人文"), "320102", 10);

            assertEquals(3, result.size());
            assertEquals("p1", result.get(0).getPoiId());
            assertEquals("p2", result.get(1).getPoiId());
            assertEquals("p3", result.get(2).getPoiId());
        }

        @Test
        @DisplayName("标签命中数量多的候选排前面")
        void moreTagMatchesRankHigher() {
            graphLoaded();
            when(graph.getAttractionsByTags(anyCollection()))
                    .thenReturn(new LinkedHashSet<>(List.of("p1", "p2")));
            when(graph.getAttractionTags("p1")).thenReturn(List.of("A", "B"));
            when(graph.getAttractionTags("p2")).thenReturn(List.of("A"));
            when(graph.expandNeighbors(anyCollection(), eq(2)))
                    .thenReturn(new LinkedHashSet<>(List.of("p2", "p1")));
            stubAttractionsByPoi();

            List<Attraction> result = service.retrieveCandidatesByGraph(List.of("A", "B"), null, 10);

            assertEquals("p1", result.get(0).getPoiId());
        }

        @Test
        @DisplayName("从种子景点沿相邻边扩展，把可串联的邻居纳入候选")
        void expandsFromSeeds() {
            graphLoaded();
            when(graph.getAttractionsByTags(anyCollection()))
                    .thenReturn(new LinkedHashSet<>(List.of("seed")));
            when(graph.expandNeighbors(anyCollection(), eq(2)))
                    .thenReturn(new LinkedHashSet<>(List.of("seed", "neighbor")));
            stubAttractionsByPoi();

            List<Attraction> result = service.retrieveCandidatesByGraph(List.of("历史人文"), null, 10);

            assertEquals(2, result.size());
            verify(graph).expandNeighbors(anyCollection(), eq(2));
        }

        @Test
        @DisplayName("缺少经纬度的景点不能进入路线规划候选")
        void skipsAttractionsWithoutLocation() {
            graphLoaded();
            when(graph.getAttractionsByTags(anyCollection()))
                    .thenReturn(new LinkedHashSet<>(List.of("p1", "p2")));
            when(graph.expandNeighbors(anyCollection(), eq(2)))
                    .thenReturn(new LinkedHashSet<>(List.of("p1", "p2")));
            when(graph.getAttraction("p1")).thenReturn(attraction("p1", "有坐标", "118.1,32.1"));
            when(graph.getAttraction("p2")).thenReturn(attraction("p2", "无坐标", null));

            List<Attraction> result = service.retrieveCandidatesByGraph(List.of("历史人文"), null, 10);

            assertEquals(1, result.size());
            assertEquals("p1", result.get(0).getPoiId());
        }

        @Test
        @DisplayName("limit 非正数时回落到默认 20 条")
        void nonPositiveLimitFallsBackToTwenty() {
            graphLoaded();
            LinkedHashSet<String> pois = new LinkedHashSet<>();
            for (int i = 0; i < 25; i++) {
                pois.add("p" + i);
            }
            when(graph.getAttractionsByTags(anyCollection())).thenReturn(pois);
            when(graph.expandNeighbors(anyCollection(), eq(2))).thenReturn(pois);
            stubAttractionsByPoi();

            assertEquals(20, service.retrieveCandidatesByGraph(List.of("历史人文"), null, 0).size());
        }

        @Test
        @DisplayName("同地区无种子时退化为用其它地区种子做扩展")
        void fallsBackToOtherRegionSeeds() {
            graphLoaded();
            when(graph.getAttractionsByTags(anyCollection()))
                    .thenReturn(new LinkedHashSet<>(List.of("p1")));
            when(graph.getAttractionRegion("p1")).thenReturn("110000");
            when(graph.expandNeighbors(anyCollection(), eq(2)))
                    .thenReturn(new LinkedHashSet<>(List.of("p1")));
            when(graph.getAttraction("p1")).thenReturn(attraction("p1", "景点", "116.1,39.1"));

            List<Attraction> result = service.retrieveCandidatesByGraph(List.of("历史人文"), "320102", 10);

            assertEquals(1, result.size());
            verify(graph).expandNeighbors(anyCollection(), eq(2));
        }
    }
}
