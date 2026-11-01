package com.viyangle.study_tour.agent.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.agent.dto.ToolEvent;
import com.viyangle.study_tour.agent.exception.AgentRunCancelledException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.function.Consumer;

/** 仅分发已注册的方法，不解释用户语义、不决定工具顺序。 */
@Slf4j
final class AgentToolExecutor {
    private final RouteAgentTools tools;
    private final ObjectMapper mapper;
    private final AgentExecutionBudget budget;
    private final Consumer<ToolEvent> events;
    private final Map<String, Method> methods = new LinkedHashMap<>();
    private final List<ToolSpecification> specifications;
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("ask_user", "等待你补充需求"), Map.entry("get_route", "读取路线草稿"),
            Map.entry("search_places", "检索候选地点"), Map.entry("search_graph_places", "查询知识图谱"),
            Map.entry("get_place_details", "核实地点详情"), Map.entry("search_reference_pairs", "查询参考组合"),
            Map.entry("calculate_leg", "查询交通段"), Map.entry("update_constraints", "更新需求"),
            Map.entry("clear_constraints", "清除指定约束"), Map.entry("edit_route", "编辑路线"),
            Map.entry("edit_transport", "编辑交通"), Map.entry("edit_schedule", "编辑时间安排"),
            Map.entry("validate_itinerary", "校验数据合法性"));

    AgentToolExecutor(RouteAgentTools tools, ObjectMapper mapper, AgentExecutionBudget budget, Consumer<ToolEvent> events) {
        this.tools = tools;
        this.mapper = mapper;
        this.budget = budget;
        this.events = events;
        Arrays.stream(RouteAgentTools.class.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(Tool.class))
                .sorted(Comparator.comparing(Method::getName)).forEach(method -> methods.put(method.getAnnotation(Tool.class).name(), method));
        specifications = methods.values().stream().map(AgentToolSchemas::specification).toList();
    }

    List<ToolSpecification> specifications() { return specifications; }

    String execute(ToolExecutionRequest call) {
        budget.consumeToolCall();
        long started = System.nanoTime();
        String label = LABELS.getOrDefault(call.name(), call.name());
        events.accept(ToolEvent.of(call.name(), label, "running", null));
        try {
            Method method = methods.get(call.name());
            if (method == null) throw new IllegalArgumentException("未知工具：" + call.name());
            String raw = call.arguments() == null ? "{}" : call.arguments();
            if (raw.length() > 128_000) throw new IllegalArgumentException("工具参数过长");
            JsonNode arguments = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException("工具参数必须为 JSON 对象");
            Set<String> names = new HashSet<>();
            for (Parameter parameter : method.getParameters()) names.add(parameter.getName());
            Iterator<String> supplied = arguments.fieldNames();
            while (supplied.hasNext()) {
                if (!names.contains(supplied.next())) throw new IllegalArgumentException("存在未定义的工具参数");
            }
            Object[] values = new Object[method.getParameterCount()];
            for (int i = 0; i < method.getParameterCount(); i++) {
                Parameter parameter = method.getParameters()[i];
                JsonNode value = arguments.get(parameter.getName());
                P annotation = parameter.getAnnotation(P.class);
                if ((value == null || value.isNull()) && (annotation == null || annotation.required())) {
                    throw new IllegalArgumentException("缺少参数：" + parameter.getName());
                }
                values[i] = value == null || value.isNull() ? null : mapper.readerFor(
                                mapper.getTypeFactory().constructType(parameter.getParameterizedType()))
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                        .readValue(value.traverse(mapper));
            }
            Object result = method.invoke(tools, values);
            budget.checkActive();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", true);
            payload.put("data", result);
            String json = mapper.writeValueAsString(payload);
            events.accept(ToolEvent.of(call.name(), label, "completed", elapsed(started)));
            return json;
        } catch (Exception exception) {
            Throwable cause = exception instanceof InvocationTargetException ? exception.getCause() : exception;
            if (cause instanceof AgentRunCancelledException cancelled) throw cancelled;
            budget.checkActive();
            String message = cause instanceof IllegalArgumentException ? cause.getMessage() : "工具执行失败，可调整参数、选择其他工具或向用户说明缺失信息";
            log.warn("Agent 工具执行失败, tool={}, exception={}", call.name(), cause.getClass().getSimpleName());
            events.accept(ToolEvent.of(call.name(), label, "needs_revision", elapsed(started)));
            try {
                return mapper.writeValueAsString(Map.of("success", false, "errorCode", "TOOL_ERROR", "message", message == null ? "无效工具参数" : message));
            } catch (Exception serializationError) {
                throw new IllegalStateException("无法序列化工具错误", serializationError);
            }
        }
    }

    private static int elapsed(long started) { return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000); }
}
