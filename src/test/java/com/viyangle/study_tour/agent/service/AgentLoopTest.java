package com.viyangle.study_tour.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.agent.config.AgentProperties;
import com.viyangle.study_tour.agent.dto.*;
import com.viyangle.study_tour.agent.exception.AgentPlanningException;
import com.viyangle.study_tour.agent.exception.AgentRunCancelledException;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.VectorRetrievalResult;
import com.viyangle.study_tour.service.*;
import com.viyangle.study_tour.utils.AmapDirectionClient;
import com.viyangle.study_tour.utils.AmapTransitClient;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentLoopTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentProperties props = new AgentProperties();
    private final ChatModel model = mock(ChatModel.class);
    private final AttractionMapper attractions = mock(AttractionMapper.class);
    private final VectorCandidateRetrieverService retriever = mock(VectorCandidateRetrieverService.class);
    private final KnowledgeGraphRecommendService graph = mock(KnowledgeGraphRecommendService.class);
    private final ReferencePairService references = mock(ReferencePairService.class);
    private final AmapTransitClient places = mock(AmapTransitClient.class);
    private final AmapDirectionClient directions = mock(AmapDirectionClient.class);
    private final List<ToolEvent> events = new ArrayList<>();
    private RouteAgentToolsFactory factory;
    private AgentLoop loop;
    private PlanRequest request;

    @BeforeEach
    void setUp() throws Exception {
        factory = new RouteAgentToolsFactory(mapper, attractions, retriever, graph, references, places, directions);
        loop = new AgentLoop(model, mapper, props, factory);
        request = new PlanRequest();
        request.setRequestId("req");
        request.setMemoryId("memory");
        request.setAccountId(1L);
        request.setMessage("南京研学，不用开车，下午早点结束");
    }

    @Test
    void sendsOriginalTextAndSchemasBeforeAnyRetrievalAndCanAnswerWithoutTools() {
        when(model.chat(any(ChatRequest.class))).thenReturn(reply("{\"kind\":\"answer\",\"message\":\"你好\"}"));
        assertThat(run()).isEqualTo(new AgentReply("你好"));
        ArgumentCaptor<ChatRequest> captured = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captured.capture());
        List<ChatMessage> messages = captured.getValue().messages();
        assertThat(((UserMessage) messages.get(messages.size() - 1)).singleText()).isEqualTo(request.getMessage());
        assertThat(captured.getValue().toolSpecifications()).extracting(spec -> spec.name())
                .contains("search_places", "search_graph_places", "get_place_details", "calculate_leg",
                        "edit_route", "edit_transport", "edit_schedule", "update_constraints", "validate_itinerary", "ask_user");
        verifyNoInteractions(attractions, retriever, graph, references, places, directions);
        assertThat(events).isEmpty();
    }

    @Test
    void announcesOnlyStageStartsBeforeToolsAndDoesNotRepeatWithinStage() {
        when(model.chat(any(ChatRequest.class))).thenReturn(
                phaseWithCalls("places", "我先筛选适合的景点。", call("p1", "get_route", "{}")),
                calls(call("p2", "get_route", "{}")),
                phaseWithCalls("transport", "接下来比较各站之间的交通方式。", call("t1", "get_route", "{}")),
                phaseWithCalls("transport", "继续比较下一段交通。", call("t2", "get_route", "{}")),
                reply("{\"kind\":\"answer\",\"message\":\"已查看\"}"));

        assertThat(run()).isInstanceOf(AgentReply.class);
        assertThat(events.stream().filter(event -> event.getTool() == null).map(ToolEvent::getLabel).toList())
                .containsExactly("我先筛选适合的景点。", "接下来比较各站之间的交通方式。");
        assertThat(events.get(0).getTool()).isNull();
        assertThat(events.get(1).getTool()).isEqualTo("get_route");
        assertThat(events.get(5).getLabel()).isEqualTo("接下来比较各站之间的交通方式。");
        verify(model, times(5)).chat(any(ChatRequest.class));
        verifyNoInteractions(retriever, directions);
    }

    @Test
    void standaloneStageMessageContinuesLoopAndReturningToEarlierStageCanAnnounceAgain() {
        when(model.chat(any(ChatRequest.class))).thenReturn(
                reply("{\"kind\":\"progress\",\"stage\":\"places\",\"message\":\"我先筛选景点。\"}"),
                phaseWithCalls("transport", "接下来比较交通。", call("t", "get_route", "{}")),
                phaseWithCalls("places", "我会根据交通情况调整景点。", call("p", "get_route", "{}")),
                reply("{\"kind\":\"answer\",\"message\":\"已查看\"}"));

        assertThat(run()).isInstanceOf(AgentReply.class);
        assertThat(events.stream().filter(event -> event.getTool() == null).map(ToolEvent::getLabel).toList())
                .containsExactly("我先筛选景点。", "接下来比较交通。", "我会根据交通情况调整景点。");
        verify(model, times(4)).chat(any(ChatRequest.class));
    }

    @Test
    void askUserDoesNotAnnounceAnAdditionalWorkStageOrContinueLoop() {
        when(model.chat(any(ChatRequest.class))).thenReturn(phaseWithCalls("places", "我先筛选景点。",
                call("ask", "ask_user", "{\"message\":\"请补充\",\"questions\":[\"每人预算是多少？\"]}")));

        assertThat(run()).isInstanceOf(Clarification.class);
        assertThat(events).extracting(ToolEvent::getTool).containsExactly("ask_user", "ask_user");
        verify(model, times(1)).chat(any(ChatRequest.class));
        verifyNoInteractions(retriever, directions);
    }

    @Test
    void invalidStageNoticeDoesNotBlockNativeToolCalls() {
        when(model.chat(any(ChatRequest.class))).thenReturn(
                phaseWithCalls("", "我先查看当前路线。", call("r", "get_route", "{}")),
                reply("{\"kind\":\"answer\",\"message\":\"已查看\"}"));

        assertThat(run()).isInstanceOf(AgentReply.class);
        assertThat(events).extracting(ToolEvent::getTool).containsExactly("get_route", "get_route");
    }

    @Test
    void preservesHistoryRolesAndTerminatesImmediatelyOnAskUser() {
        Turn previous = new Turn();
        previous.setRole("assistant");
        previous.setContent("想去哪座城市？");
        request.setHistory(List.of(previous));
        when(model.chat(any(ChatRequest.class))).thenReturn(calls(call("ask", "ask_user",
                "{\"message\":\"请补充\",\"questions\":[\"研学 tag 和每人预算分别是多少？\"]}")));
        assertThat(run()).isInstanceOf(Clarification.class);
        verifyNoInteractions(attractions, retriever, graph);
        ArgumentCaptor<ChatRequest> captured = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captured.capture());
        assertThat(captured.getValue().messages().get(1)).isInstanceOf(AiMessage.class);
    }

    @Test
    void askUserPreemptsOtherCallsInSameBatchAndMissingFieldsStayMissing() {
        when(model.chat(any(ChatRequest.class))).thenReturn(calls(
                call("search", "search_places", "{\"query\":\"南京\"}"),
                call("ask", "ask_user", "{\"message\":\"缺少规划信息\",\"questions\":[\"希望什么研学 tag？\",\"每人预算多少？\"]}")));
        Clarification result = (Clarification) run();
        assertThat(result.getQuestions()).hasSize(2);
        assertThat(result.getConstraints().getBudget()).isNull();
        assertThat(result.getConstraints().getDays()).isNull();
        assertThat(result.getConstraints().getInterests()).isNull();
        verify(model, times(1)).chat(any(ChatRequest.class));
        verifyNoInteractions(retriever, attractions, directions, graph);
        assertThat(events).extracting(ToolEvent::getTool).containsExactly("ask_user", "ask_user");
    }

    @Test
    void malformedAskUserCanBeCorrectedWithoutExecutingSkippedTools() {
        when(model.chat(any(ChatRequest.class))).thenReturn(
                calls(call("ask1", "ask_user", "{\"message\":\"缺少信息\",\"questions\":[]}"),
                        call("s", "search_places", "{\"query\":\"南京\"}")),
                calls(call("ask2", "ask_user", "{\"message\":\"请补充\",\"questions\":[\"预算多少？\"]}")));
        assertThat(run()).isInstanceOf(Clarification.class);
        verifyNoInteractions(retriever, attractions, directions, graph);
        ArgumentCaptor<ChatRequest> captured = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captured.capture());
        assertThat(captured.getAllValues().get(1).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance).map(ToolExecutionResultMessage.class::cast)
                .map(ToolExecutionResultMessage::id).toList()).containsExactly("ask1", "s");
    }

    @Test
    void returnsEveryToolResultWithMatchingIdAndLetsModelChooseNextStep() {
        when(model.chat(any(ChatRequest.class))).thenReturn(
                calls(call("c1", "get_route", "{}"), call("c2", "unknown_tool", "{}")),
                reply("{\"kind\":\"answer\",\"message\":\"已查看当前草稿\"}"));
        assertThat(run()).isInstanceOf(AgentReply.class);
        ArgumentCaptor<ChatRequest> captured = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captured.capture());
        List<ToolExecutionResultMessage> results = captured.getAllValues().get(1).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance).map(ToolExecutionResultMessage.class::cast).toList();
        assertThat(results).extracting(ToolExecutionResultMessage::id).containsExactly("c1", "c2");
        assertThat(results.get(0).text()).contains("\"success\":true");
        assertThat(results.get(1).text()).contains("\"success\":false");
        assertThat(events).extracting(ToolEvent::getTool).containsExactly("get_route", "get_route", "unknown_tool", "unknown_tool");
        verifyNoInteractions(retriever, graph, directions);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void canEditRouteTransportAndScheduleAndBackendDoesNotOverrideBusinessDecisions(boolean feasible) {
        Constraints constraints = new Constraints();
        constraints.setDailyMinutes(30);
        constraints.setMaxWalkingMeters(100);
        constraints.setBudget(1.0);
        constraints.setMustVisit(List.of("另一景点"));
        request.setConstraints(constraints);
        when(attractions.selectByPoiId("A")).thenReturn(attraction("A"));
        when(attractions.selectByPoiId("B")).thenReturn(attraction("B"));
        when(model.chat(any(ChatRequest.class))).thenReturn(
                calls(call("r", "edit_route", "{\"stops\":[{\"poiId\":\"A\",\"recommendedDuration\":120},{\"poiId\":\"B\",\"recommendedDuration\":60}]}"),
                        call("t", "edit_transport", transportJson()),
                        call("s", "edit_schedule", scheduleJson())),
                reply("{\"kind\":\"plan\",\"message\":\"模型提出的方案\",\"feasible\":" + feasible + ",\"issues\":[\"需要放宽条件\"],\"estimatedCost\":40,\"unknownCosts\":2}"));
        AgentPlan plan = (AgentPlan) run();
        assertThat(plan.getFeasible()).isEqualTo(feasible);
        assertThat(plan.getNodes()).extracting(RouteNode::getPoiId).containsExactly("A", "B");
        assertThat(plan.getDays().get(0).getTotalMinutes()).isEqualTo(300);
        assertThat(plan.getDays().get(0).getSchedule().get(0).getDeparture()).isEqualTo("11:00");
        assertThat(plan.getLegs().get(0).getMode()).isEqualTo("walking");
        assertThat(plan.getLegs().get(0).getMinutes()).isEqualTo(30);
        assertThat(plan.getEstimatedCost()).isEqualTo(40);
        assertThat(plan.getConstraints().getMustVisit()).containsExactly("另一景点");
        verifyNoInteractions(retriever, graph, directions);
    }

    @Test
    void invalidToolArgumentsAreReturnedToModelAndCanBeCorrected() {
        when(attractions.selectByPoiId("A")).thenReturn(attraction("A"));
        when(model.chat(any(ChatRequest.class))).thenReturn(
                calls(call("bad", "edit_route", "{\"stops\":[{\"poiId\":\"A\",\"recommendedDuration\":-1}]}")),
                calls(call("good", "edit_route", "{\"stops\":[{\"poiId\":\"A\",\"recommendedDuration\":60}]}")),
                reply("{\"kind\":\"answer\",\"message\":\"已修正\"}"));
        run();
        assertThat(events).extracting(ToolEvent::getStatus).containsExactly("running", "needs_revision", "running", "completed");
    }

    @Test
    void finalPlanCannotInventNodesOutsideToolDraft() {
        when(model.chat(any(ChatRequest.class))).thenReturn(
                reply("{\"kind\":\"plan\",\"message\":\"编造路线\",\"feasible\":true}"),
                calls(call("ask", "ask_user", "{\"message\":\"请提供地点\",\"questions\":[\"去哪？\"]}")));
        assertThat(run()).isInstanceOf(Clarification.class);
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void cancelledAfterModelCallDoesNotExecuteItsTools() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            cancelled.set(true);
            return calls(call("r", "search_places", "{\"query\":\"南京\"}"));
        });
        assertThatThrownBy(() -> loop.run(request, System.currentTimeMillis(), events::add, cancelled::get))
                .isInstanceOf(AgentRunCancelledException.class);
        verifyNoInteractions(retriever, graph, attractions);
    }

    @Test
    void capsToolCallsAcrossModelTurns() {
        props.setMaxToolCalls(1);
        when(model.chat(any(ChatRequest.class))).thenReturn(calls(call("1", "get_route", "{}")), calls(call("2", "get_route", "{}")));
        assertThatThrownBy(this::run).isInstanceOf(AgentPlanningException.class).hasMessageContaining("工具调用上限");
    }

    @Test
    void capsModelTurnsAndHonorsDeadline() {
        props.setMaxModelTurns(1);
        when(model.chat(any(ChatRequest.class))).thenReturn(calls(call("1", "get_route", "{}")));
        assertThatThrownBy(this::run).isInstanceOf(AgentPlanningException.class).hasMessageContaining("模型交互上限");
        props.setExecutionTimeout(Duration.ofMillis(1));
        assertThatThrownBy(() -> loop.run(request, System.currentTimeMillis() - 100, events::add, () -> false))
                .isInstanceOf(AgentPlanningException.class).hasMessageContaining("超时");
    }

    @Test
    void cachesDirectionalMapFactsAndSharesBudgetAcrossQueries() {
        props.setMaxDirectionRequests(1);
        when(attractions.selectByPoiId("A")).thenReturn(attraction("A"));
        when(attractions.selectByPoiId("B")).thenReturn(attraction("B"));
        AmapDirectionClient.LegResult facts = new AmapDirectionClient.LegResult();
        facts.setMinutes(30);
        when(directions.route(any(), anyString(), anyString(), nullable(String.class))).thenReturn(facts);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                calls(call("1", "calculate_leg", "{\"fromPoiId\":\"A\",\"toPoiId\":\"B\",\"mode\":\"transit\"}"),
                        call("2", "calculate_leg", "{\"fromPoiId\":\"A\",\"toPoiId\":\"B\",\"mode\":\"transit\"}"),
                        call("3", "calculate_leg", "{\"fromPoiId\":\"B\",\"toPoiId\":\"A\",\"mode\":\"transit\"}")),
                reply("{\"kind\":\"answer\",\"message\":\"已查询\"}"));
        run();
        verify(directions, times(1)).route(any(), anyString(), anyString(), nullable(String.class));
        assertThat(events.get(events.size() - 1).getStatus()).isEqualTo("needs_revision");
    }

    @Test
    void perRunDraftsAreIsolatedAndRouteEditsClearStaleTransportAndTimes() {
        when(attractions.selectByPoiId("A")).thenReturn(attraction("A"));
        when(attractions.selectByPoiId("B")).thenReturn(attraction("B"));
        AgentExecutionBudget budget = new AgentExecutionBudget(props, System.currentTimeMillis(), () -> false);
        RouteAgentTools first = factory.create(request, budget);
        RouteAgentTools second = factory.create(request, budget);
        first.editRoute(List.of(new RouteAgentTools.RouteStop("A", 60, ""), new RouteAgentTools.RouteStop("B", 60, "")));
        Leg leg = new Leg();
        leg.setDay(1); leg.setOrigin("A"); leg.setDestination("B"); leg.setMode("walking");
        first.editTransport(List.of(leg));
        first.editRoute(List.of(new RouteAgentTools.RouteStop("B", 90, "")));
        assertThat(first.getRoute().getLegs()).isEmpty();
        assertThat(first.getRoute().getDays()).isEmpty();
        assertThat(second.getRoute().getNodes()).isEmpty();
    }

    @Test
    void ragToolUsesRequestedQueryAndReturnsActualPoiFacts() {
        when(retriever.retrieveCandidatesWithTexts("博物馆", 60, 5)).thenReturn(new VectorRetrievalResult(List.of("A"), List.of("片段")));
        when(attractions.selectActiveByPoiIds(List.of("A"))).thenReturn(List.of(attraction("A")));
        when(model.chat(any(ChatRequest.class))).thenReturn(
                calls(call("s", "search_places", "{\"query\":\"博物馆\",\"city\":\"南京\",\"limit\":3}")),
                reply("{\"kind\":\"answer\",\"message\":\"已找到景点\"}"));
        run();
        verify(retriever).retrieveCandidatesWithTexts("博物馆", 60, 5);
        assertThat(events).extracting(ToolEvent::getStatus).containsExactly("running", "completed");
    }

    @Test
    void modelCanUpdateAndClearConstraintsWithoutMutatingSubmittedContext() {
        Constraints original = new Constraints();
        original.setBudget(100.0);
        original.setDays(2);
        request.setConstraints(original);
        RouteAgentTools tools = factory.create(request, new AgentExecutionBudget(props, System.currentTimeMillis(), () -> false));
        Constraints patch = new Constraints();
        patch.setInterests(List.of("历史人文"));
        assertThat(tools.updateConstraints(patch).getBudget()).isEqualTo(100.0);
        assertThat(tools.clearConstraints(List.of("budget")).getBudget()).isNull();
        assertThat(tools.getRoute().getConstraints().getDays()).isEqualTo(2);
        assertThat(request.getConstraints().getBudget()).isEqualTo(100.0);
        assertThatThrownBy(() -> tools.clearConstraints(List.of("days", "undefined"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(tools.getRoute().getConstraints().getDays()).isEqualTo(2);
    }

    private Object run() { return loop.run(request, System.currentTimeMillis(), events::add, () -> false); }
    private static ChatResponse reply(String text) { return ChatResponse.builder().aiMessage(AiMessage.from(text)).build(); }
    private static ChatResponse calls(ToolExecutionRequest... calls) { return ChatResponse.builder().aiMessage(AiMessage.from(List.of(calls))).build(); }
    private ChatResponse phaseWithCalls(String stage, String message, ToolExecutionRequest... calls) {
        try {
            String text = mapper.writeValueAsString(Map.of("kind", "progress", "stage", stage, "message", message));
            return ChatResponse.builder().aiMessage(AiMessage.from(text, List.of(calls))).build();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
    private static ToolExecutionRequest call(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }
    private static Attraction attraction(String id) {
        Attraction place = new Attraction();
        place.setPoiId(id); place.setName("景点" + id); place.setLocation("118.8,32.0"); place.setCityname("南京市");
        return place;
    }
    private static String transportJson() {
        return "{\"legs\":[{\"day\":1,\"origin\":\"A\",\"destination\":\"B\",\"mode\":\"walking\",\"minutes\":30,\"walkingMeters\":2000,\"cost\":40}]}";
    }
    private static String scheduleJson() {
        return "{\"days\":[{\"day\":1,\"totalMinutes\":300,\"schedule\":[{\"poiId\":\"A\",\"arrival\":\"09:00\",\"departure\":\"11:00\",\"minutes\":120},{\"poiId\":\"B\",\"arrival\":\"11:30\",\"departure\":\"12:30\",\"minutes\":60}]}]}";
    }
}
