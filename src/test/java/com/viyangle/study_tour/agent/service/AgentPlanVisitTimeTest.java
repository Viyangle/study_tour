package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.config.AgentProperties;
import com.viyangle.study_tour.agent.dto.AgentPlan;
import com.viyangle.study_tour.agent.dto.Constraints;
import com.viyangle.study_tour.agent.dto.PlanRequest;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归：docs/API.md 与 docs/openapi.json 要求方案节点带 {@code visitTime}
 * （“日期或第N天 + HH:mm”），此前该字段一直为 null。
 */
class AgentPlanVisitTimeTest {

    private AttractionMapper attractionMapper;
    private AmapDirectionClient directionClient;
    private AgentPlanAssembler assembler;

    @BeforeEach
    void setUp() {
        attractionMapper = mock(AttractionMapper.class);
        AmapTransitClient transitClient = mock(AmapTransitClient.class);
        directionClient = mock(AmapDirectionClient.class);
        AgentProperties props = new AgentProperties();
        props.setMaxDirectionRequests(90);
        assembler = new AgentPlanAssembler(attractionMapper, transitClient, directionClient, props);
    }

    @Test
    void nodesCarryVisitTimeFromDaySchedule() {
        when(attractionMapper.selectByPoiIds(anyList())).thenReturn(List.of(
                attraction("POI_A", "示例博物馆A", "118.790000,32.060000"),
                attraction("POI_B", "示例场馆B", "118.800000,32.060000")));
        when(directionClient.route(any(), any(), any(), any()))
                .thenReturn(leg("walking", 20, 1200, 1200));

        Constraints constraints = constraints("2026-10-11");
        AgentPlan plan = assemble(constraints);

        assertNotNull(plan);
        assertEquals("2026-10-11 09:00", plan.getNodes().get(0).getVisitTime());
        // 第一站 09:00 到达、停留 60 分钟，交通 20 分钟 + 5 分钟缓冲
        assertEquals("2026-10-11 10:25", plan.getNodes().get(1).getVisitTime());
        assertEquals("09:00", plan.getDays().get(0).getSchedule().get(0).getArrival());
    }

    @Test
    void nodesUseDayPrefixWhenNoTravelDateProvided() {
        when(attractionMapper.selectByPoiIds(anyList())).thenReturn(List.of(
                attraction("POI_A", "示例博物馆A", "118.790000,32.060000"),
                attraction("POI_B", "示例场馆B", "118.800000,32.060000")));
        when(directionClient.route(any(), any(), any(), any()))
                .thenReturn(leg("walking", 20, 1200, 1200));

        AgentPlan plan = assemble(constraints(null));

        assertNotNull(plan);
        assertEquals("第1天 09:00", plan.getNodes().get(0).getVisitTime());
        assertEquals("第1天 10:25", plan.getNodes().get(1).getVisitTime());
    }

    private AgentPlan assemble(Constraints constraints) {
        PlanRequest request = new PlanRequest();
        request.setRequestId("req-visittime");
        request.setMemoryId("mem-visittime");
        request.setAccountId(1L);
        request.setMessage("南京亲子历史研学");
        request.setConstraints(constraints);

        AgentPlanAssembler.Outcome outcome =
                assembler.assemble(request, aiPlan("POI_A", "POI_B"), event -> {
                }, () -> false);
        return outcome.getPlan();
    }

    private Constraints constraints(String date) {
        Constraints constraints = new Constraints();
        constraints.setCity("南京");
        constraints.setDate(date);
        constraints.setDays(1);
        constraints.setDailyMinutes(480);
        constraints.setStartTime("09:00");
        constraints.setModes(new ArrayList<>(List.of("walking", "transit")));
        return constraints;
    }

    private AIRoutePlan aiPlan(String... poiIds) {
        AIRoutePlan plan = new AIRoutePlan();
        plan.setTag("博物馆研学");
        List<AIRouteItem> items = new ArrayList<>();
        for (int i = 0; i < poiIds.length; i++) {
            AIRouteItem item = new AIRouteItem();
            item.setPoiId(poiIds[i]);
            item.setVisitOrder(i + 1);
            item.setRecommendedDuration(60);
            item.setNotes("观察历史展陈并完成记录卡");
            items.add(item);
        }
        plan.setItems(items);
        return plan;
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

    private AmapDirectionClient.LegResult leg(String mode, int minutes, int distance, Integer walking) {
        AmapDirectionClient.LegResult result = new AmapDirectionClient.LegResult();
        result.setMinutes(minutes);
        result.setDistanceMeters(distance);
        result.setWalkingMeters(walking);
        result.setCost(null);
        result.setPolyline(List.of(List.of(118.79, 32.06), List.of(118.80, 32.06)));
        result.setInstructions(List.of("示例步行说明"));
        result.setSource("高德路径规划");
        result.setNote("查询时参考结果，不保证未来交通情况");
        return result;
    }
}
