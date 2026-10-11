package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.config.AgentProperties;
import com.viyangle.study_tour.agent.dto.AgentPlan;
import com.viyangle.study_tour.agent.dto.Constraints;
import com.viyangle.study_tour.agent.dto.CurrentStop;
import com.viyangle.study_tour.agent.dto.DaySummary;
import com.viyangle.study_tour.agent.dto.Leg;
import com.viyangle.study_tour.agent.dto.PlanRequest;
import com.viyangle.study_tour.agent.dto.ScheduleItem;
import com.viyangle.study_tour.agent.dto.ToolEvent;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.utils.AmapDirectionClient;
import com.viyangle.study_tour.utils.AmapTransitClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 方案装配：地点详情、每日行程、交通段、成本与追问分支。 */
class AgentPlanAssemblerTest {

    private AttractionMapper attractionMapper;
    private AmapDirectionClient directionClient;
    private AgentPlanAssembler assembler;
    private final List<ToolEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        attractionMapper = mock(AttractionMapper.class);
        AmapTransitClient transitClient = mock(AmapTransitClient.class);
        directionClient = mock(AmapDirectionClient.class);
        AgentProperties props = new AgentProperties();
        props.setMaxDirectionRequests(90);
        assembler = new AgentPlanAssembler(attractionMapper, transitClient, directionClient, props);
        events.clear();
    }

    private Attraction attraction(String poiId, String name, String location) {
        Attraction attraction = new Attraction();
        attraction.setPoiId(poiId);
        attraction.setName(name);
        attraction.setLocation(location);
        attraction.setAddress("示例地址");
        attraction.setCityname("南京市");
        attraction.setCitycode("025");
        attraction.setAdcode("320102");
        attraction.setType("科教文化服务");
        attraction.setTypecode("140100");
        attraction.setTel("025-00000000");
        attraction.setOpentimeToday("09:00-17:00");
        attraction.setOpentimeWeek("");
        return attraction;
    }

    private AmapDirectionClient.LegResult leg(String mode, int minutes, int distance, Integer walking,
                                              Double cost) {
        AmapDirectionClient.LegResult result = new AmapDirectionClient.LegResult();
        result.setMinutes(minutes);
        result.setDistanceMeters(distance);
        result.setWalkingMeters(walking);
        result.setCost(cost);
        result.setPolyline(List.of(List.of(118.79, 32.06), List.of(118.80, 32.06)));
        result.setInstructions(List.of("沿示例路步行"));
        result.setSource("高德路径规划");
        result.setNote("查询时参考结果，不保证未来交通情况");
        return result;
    }

    private AIRoutePlan plan(String... poiIds) {
        AIRoutePlan plan = new AIRoutePlan();
        plan.setTag("博物馆研学");
        List<AIRouteItem> items = new ArrayList<>();
        for (int i = 0; i < poiIds.length; i++) {
            AIRouteItem item = new AIRouteItem();
            item.setPoiId(poiIds[i]);
            item.setVisitOrder(i + 1);
            item.setRecommendedDuration(60);
            item.setNotes(noteOf(poiIds[i]));
            items.add(item);
        }
        plan.setItems(items);
        return plan;
    }

    private String noteOf(String poiId) {
        return switch (poiId) {
            case "POI_B" -> "步行前往下一站";
            case "POI_C" -> "比较两处展陈的历史叙事";
            default -> "观察历史展陈并完成记录卡";
        };
    }

    private PlanRequest request(Constraints constraints) {
        PlanRequest request = new PlanRequest();
        request.setRequestId("req-1");
        request.setMemoryId("mem-1");
        request.setAccountId(1L);
        request.setMessage("南京亲子历史研学");
        request.setConstraints(constraints);
        return request;
    }

    private Constraints constraints() {
        Constraints constraints = new Constraints();
        constraints.setCity("南京");
        constraints.setDays(1);
        constraints.setDailyMinutes(480);
        constraints.setStartTime("09:00");
        constraints.setModes(new ArrayList<>(List.of("walking", "transit")));
        return constraints;
    }

    @Test
    void buildsPlanWithDaysLegsAndHonestCosts() {
        when(attractionMapper.selectByPoiIds(anyList())).thenReturn(List.of(
                attraction("POI_A", "示例博物馆A", "118.790000,32.060000"),
                attraction("POI_B", "示例场馆B", "118.800000,32.060000")));
        when(directionClient.route(any(), any(), any(), any()))
                .thenReturn(leg("walking", 20, 1200, 1200, null));

        PlanRequest request = request(constraints());
        request.setCurrentRoute(List.of(currentStop("POI_X", "旧地点")));
        AgentPlanAssembler.Outcome outcome =
                assembler.assemble(request, plan("POI_A", "POI_B"), events::add, () -> false);

        assertNull(outcome.getClarification());
        AgentPlan plan = outcome.getPlan();
        assertNotNull(plan);
        assertEquals("plan", plan.getKind());
        assertTrue(plan.getFeasible());
        assertTrue(plan.getIssues().isEmpty());
        assertTrue(plan.getRequiresAcceptance());
        assertEquals(2, plan.getNodes().size());
        assertEquals("POI_A", plan.getNodes().get(0).getPoiId());
        assertEquals(1, plan.getNodes().get(0).getVisitOrder());
        assertEquals(60, plan.getNodes().get(0).getRecommendedDuration());

        assertEquals(1, plan.getDays().size());
        DaySummary day = plan.getDays().get(0);
        assertEquals(1, day.getDay());
        assertEquals(145, day.getTotalMinutes());
        assertEquals(1200, day.getWalkingMeters());
        assertEquals(0.0, day.getEstimatedCost());
        assertEquals(1, day.getLegCount());
        assertEquals("11:25", day.getFinishTime());
        assertEquals(2, day.getSchedule().size());
        ScheduleItem first = day.getSchedule().get(0);
        assertEquals("09:00", first.getArrival());
        assertEquals("10:00", first.getDeparture());
        assertEquals("观察历史展陈并完成记录卡", first.getLearningGoal());

        assertEquals(1, plan.getLegs().size());
        Leg leg = plan.getLegs().get(0);
        assertEquals("walking", leg.getMode());
        assertEquals(20, leg.getMinutes());
        assertEquals(2, leg.getPolyline().size());
        assertEquals(2, plan.getAddedPoiIds().size());
        assertEquals(List.of("POI_X"), plan.getRemovedPoiIds());

        // 景点票价数据缺失，因此每个地点都记为未核实费用；步行段费用同样未知。
        assertEquals(3, plan.getUnknownCosts());
        assertEquals(0.0, plan.getEstimatedCost());
        assertTrue(plan.getWarnings().stream().anyMatch(w -> w.contains("有 3 项费用未核实")));
        assertTrue(events.stream().anyMatch(e -> "get_place_details".equals(e.getTool())));
        assertTrue(events.stream().anyMatch(e -> "calculate_leg".equals(e.getTool())));
        assertTrue(events.stream().anyMatch(e -> "finish_plan".equals(e.getTool())));
    }

    @Test
    void fallsBackToStraightLineEstimateWhenDirectionUnavailable() {
        when(attractionMapper.selectByPoiIds(anyList())).thenReturn(List.of(
                attraction("POI_A", "示例博物馆A", "118.790000,32.060000"),
                attraction("POI_B", "示例场馆B", "118.800000,32.060000")));
        when(directionClient.route(any(), any(), any(), any())).thenReturn(null);

        AgentPlanAssembler.Outcome outcome =
                assembler.assemble(request(constraints()), plan("POI_A", "POI_B"), events::add, () -> false);
        AgentPlan plan = outcome.getPlan();
        assertEquals("直线估算", plan.getLegs().get(0).getSource());
        assertTrue(plan.getLegs().get(0).getPolyline().isEmpty());
        assertTrue(plan.getWarnings().stream().anyMatch(w -> w.contains("直线估算")));
        assertTrue(events.stream().anyMatch(e -> "calculate_leg".equals(e.getTool())
                && "needs_revision".equals(e.getStatus())));
    }

    @Test
    void asksClarificationWhenMustVisitCannotBeArranged() {
        when(attractionMapper.selectByPoiIds(anyList())).thenReturn(List.of(
                attraction("POI_A", "示例博物馆A", "118.790000,32.060000"),
                attraction("POI_B", "示例场馆B", "118.800000,32.060000")));
        when(directionClient.route(any(), any(), any(), any()))
                .thenReturn(leg("walking", 20, 1200, 1200, null));

        Constraints constraints = constraints();
        constraints.setMustVisit(new ArrayList<>(List.of("南京博物院")));
        AgentPlanAssembler.Outcome outcome =
                assembler.assemble(request(constraints), plan("POI_A", "POI_B"), events::add, () -> false);

        assertNull(outcome.getPlan());
        assertNotNull(outcome.getClarification());
        assertEquals("clarification", outcome.getClarification().getKind());
        assertTrue(outcome.getClarification().getQuestions().get(0).contains("南京博物院"));
        assertTrue(outcome.getClarification().getQuestions().size() <= 3);
        assertTrue(outcome.getClarification().getNodes().isEmpty());
    }

    @Test
    void switchesAwayFromWalkingWhenWalkingLimitExceeded() {
        when(attractionMapper.selectByPoiIds(anyList())).thenReturn(List.of(
                attraction("POI_A", "示例博物馆A", "118.790000,32.060000"),
                attraction("POI_B", "示例场馆B", "118.800000,32.060000")));
        when(directionClient.route(eq(AmapDirectionClient.Mode.WALKING), any(), any(), any()))
                .thenReturn(leg("walking", 20, 1200, 1200, null));
        when(directionClient.route(eq(AmapDirectionClient.Mode.TRANSIT), any(), any(), any()))
                .thenReturn(leg("transit", 25, 3000, 150, 2.0));

        Constraints constraints = constraints();
        constraints.setMaxWalkingMeters(500);
        AgentPlanAssembler.Outcome outcome =
                assembler.assemble(request(constraints), plan("POI_A", "POI_B"), events::add, () -> false);

        AgentPlan plan = outcome.getPlan();
        assertNotNull(plan);
        assertEquals("transit", plan.getLegs().get(0).getMode());
        assertEquals(150, plan.getDays().get(0).getWalkingMeters());
        assertEquals(2.0, plan.getDays().get(0).getEstimatedCost());
        assertEquals(2.0, plan.getEstimatedCost());
        assertEquals(2, plan.getUnknownCosts());
        assertTrue(plan.getAssumptions().stream().anyMatch(a -> a.contains("步行段改为公共交通")));
    }

    @Test
    void asksClarificationWhenDayBudgetExceeded() {
        when(attractionMapper.selectByPoiIds(anyList())).thenReturn(List.of(
                attraction("POI_A", "示例博物馆A", "118.790000,32.060000"),
                attraction("POI_B", "示例场馆B", "118.800000,32.060000"),
                attraction("POI_C", "示例场馆C", "118.810000,32.060000")));
        when(directionClient.route(any(), any(), any(), any()))
                .thenReturn(leg("walking", 20, 1200, 1200, null));

        Constraints constraints = constraints();
        constraints.setDailyMinutes(60);
        AgentPlanAssembler.Outcome outcome =
                assembler.assemble(request(constraints), plan("POI_A", "POI_B", "POI_C"), events::add, () -> false);
        assertNotNull(outcome.getClarification());
        assertTrue(outcome.getClarification().getQuestions().get(0).contains("分钟"));
    }

    @Test
    void ordersNodesByVisitOrder() {
        when(attractionMapper.selectByPoiIds(anyList())).thenReturn(List.of(
                attraction("POI_A", "示例博物馆A", "118.790000,32.060000"),
                attraction("POI_B", "示例场馆B", "118.800000,32.060000")));
        when(directionClient.route(any(), any(), any(), any()))
                .thenReturn(leg("walking", 20, 1200, 1200, null));

        AIRoutePlan unordered = new AIRoutePlan();
        unordered.setTag("博物馆研学");
        AIRouteItem first = new AIRouteItem();
        first.setPoiId("POI_A");
        first.setVisitOrder(2);
        first.setRecommendedDuration(60);
        AIRouteItem second = new AIRouteItem();
        second.setPoiId("POI_B");
        second.setVisitOrder(1);
        second.setRecommendedDuration(60);
        unordered.setItems(List.of(first, second));

        AgentPlan plan = assembler.assemble(request(constraints()), unordered, events::add, () -> false).getPlan();
        assertNotNull(plan);
        assertEquals("POI_B", plan.getNodes().get(0).getPoiId());
        assertEquals("POI_A", plan.getNodes().get(1).getPoiId());
        assertEquals(1, plan.getNodes().get(0).getVisitOrder());
        assertEquals(2, plan.getNodes().get(1).getVisitOrder());
    }

    private CurrentStop currentStop(String poiId, String name) {
        CurrentStop stop = new CurrentStop();
        stop.setPoiId(poiId);
        stop.setName(name);
        return stop;
    }
}
