package com.viyangle.study_tour.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.viyangle.study_tour.agent.dto.DaySummary;
import com.viyangle.study_tour.agent.dto.Leg;
import com.viyangle.study_tour.agent.dto.ScheduleItem;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;

import java.lang.reflect.*;
import java.util.*;

/** 兼容当前 LangChain4j 1.0.1：其默认工具 schema 转换不支持 List<List<Double>>。 */
final class AgentToolSchemas {
    private AgentToolSchemas() {}

    static ToolSpecification specification(Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        JsonObjectSchema.Builder parameters = JsonObjectSchema.builder().additionalProperties(false);
        List<String> required = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            P annotation = parameter.getAnnotation(P.class);
            parameters.addProperty(parameter.getName(), schema(parameter.getParameterizedType(), annotation == null ? null : annotation.value()));
            if (annotation == null || annotation.required()) required.add(parameter.getName());
        }
        return ToolSpecification.builder().name(tool.name()).description(String.join("\n", tool.value()))
                .parameters(parameters.required(required).build()).build();
    }

    private static JsonSchemaElement schema(Type type, String description) {
        if (type instanceof ParameterizedType generic && generic.getRawType() == List.class) {
            return JsonArraySchema.builder().description(description).items(schema(generic.getActualTypeArguments()[0], null)).build();
        }
        if (!(type instanceof Class<?> clazz)) throw new IllegalArgumentException("不支持的工具参数类型：" + type);
        if (clazz == String.class) return JsonStringSchema.builder().description(description).build();
        if (clazz == Integer.class) return JsonIntegerSchema.builder().description(description).build();
        if (clazz == Double.class) return JsonNumberSchema.builder().description(description).build();
        if (clazz == Boolean.class) return JsonBooleanSchema.builder().description(description).build();
        JsonObjectSchema.Builder object = JsonObjectSchema.builder().description(description).additionalProperties(false);
        for (Field field : clazz.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic() && !field.isAnnotationPresent(JsonIgnore.class)) {
                object.addProperty(field.getName(), schema(field.getGenericType(), null));
            }
        }
        List<String> required = clazz == Leg.class ? List.of("day", "origin", "destination", "mode")
                : clazz == DaySummary.class ? List.of("day", "schedule")
                : clazz == ScheduleItem.class ? List.of("arrival", "departure", "minutes")
                : clazz == RouteAgentTools.RouteStop.class ? List.of("poiId", "recommendedDuration") : List.of();
        return object.required(required).build();
    }
}
