package com.viyangle.study_tour.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.agent.dto.PlanRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 校验器按 openapi.json 约束逐项检查，并输出 FastAPI 风格的 detail。 */
class PlanRequestValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlanRequestValidator validator = new PlanRequestValidator();

    private PlanRequest parse(String json) throws Exception {
        return objectMapper.readValue(json, PlanRequest.class);
    }

    private static String minimal() {
        return """
                {
                  "requestId": "req-1",
                  "memoryId": "mem-1",
                  "accountId": 1,
                  "message": "南京亲子历史研学"
                }
                """;
    }

    @Test
    void acceptsMinimalRequest() throws Exception {
        assertTrue(validator.validate(parse(minimal())).isEmpty());
    }

    @Test
    void acceptsFullRequest() throws Exception {
        String json = """
                {
                  "requestId": "req-1",
                  "memoryId": "mem-1",
                  "accountId": 1,
                  "message": "南京亲子历史研学",
                  "history": [{"role": "user", "content": "你好"}],
                  "currentRoute": [{"poiId": "B00190BBCZ", "name": "玄武湖景区", "visitOrder": 1}],
                  "constraints": {
                    "city": "南京",
                    "date": "2026-10-11",
                    "days": 2,
                    "dailyMinutes": 240,
                    "startTime": "09:00",
                    "finishByTime": "13:00",
                    "budget": 200,
                    "maxWalkingMeters": 2000,
                    "modes": ["walking", "transit"],
                    "mustVisit": ["南京博物院"],
                    "avoid": [],
                    "interests": ["历史文化"],
                    "group": "2名成人和1名8岁儿童",
                    "accessibility": "",
                    "pace": "relaxed",
                    "returnToStart": false
                  },
                  "origin": {"longitude": 118.79, "latitude": 32.06}
                }
                """;
        assertTrue(validator.validate(parse(json)).isEmpty());
    }

    @Test
    void rejectsUnknownTopLevelField() throws Exception {
        String json = minimal().replace("\"accountId\": 1,", "\"accountId\": 1, \"extra\": 1,");
        List<Map<String, Object>> errors = validator.validate(parse(json));
        assertEquals(1, errors.size());
        assertEquals("extra_forbidden", errors.get(0).get("type"));
        assertEquals(List.of("body", "extra"), errors.get(0).get("loc"));
        assertEquals("Extra inputs are not permitted", errors.get(0).get("msg"));
    }

    @Test
    void rejectsUnknownNestedFieldButIgnoresCurrentRouteExtras() throws Exception {
        String json = """
                {
                  "requestId": "req-1",
                  "memoryId": "mem-1",
                  "accountId": 1,
                  "message": "南京研学",
                  "constraints": {"city": "南京", "unknown": true},
                  "currentRoute": [{"poiId": "P1", "name": "x", "visitOrder": 1, "location": "1,2"}]
                }
                """;
        List<Map<String, Object>> errors = validator.validate(parse(json));
        assertEquals(1, errors.size());
        assertEquals(List.of("body", "constraints", "unknown"), errors.get(0).get("loc"));
    }

    @Test
    void reportsMissingRequiredFields() throws Exception {
        List<Map<String, Object>> errors = validator.validate(parse("{}"));
        List<Object> locations = errors.stream().map(entry -> entry.get("loc")).toList();
        assertTrue(locations.contains(List.of("body", "requestId")));
        assertTrue(locations.contains(List.of("body", "memoryId")));
        assertTrue(locations.contains(List.of("body", "accountId")));
        assertTrue(locations.contains(List.of("body", "message")));
        assertTrue(errors.stream().allMatch(entry -> "missing".equals(entry.get("type"))));
    }

    @Test
    void rejectsBlankMessageAndBadAccountId() throws Exception {
        List<Map<String, Object>> errors = validator.validate(parse("""
                {"requestId": "req-1", "memoryId": "mem-1", "accountId": 0, "message": "   "}
                """));
        assertTrue(errors.stream().anyMatch(entry -> "string_too_short".equals(entry.get("type"))
                && List.of("body", "message").equals(entry.get("loc"))));
        assertTrue(errors.stream().anyMatch(entry -> "greater_than".equals(entry.get("type"))
                && Map.of("gt", 0).equals(entry.get("ctx"))));
    }

    @Test
    void rejectsPatternAndRangeViolations() throws Exception {
        List<Map<String, Object>> errors = validator.validate(parse("""
                {
                  "requestId": "有 空格",
                  "memoryId": "mem-1",
                  "accountId": 1,
                  "message": "研学",
                  "constraints": {"days": 10, "dailyMinutes": 5, "pace": "fast", "date": "2026/10/11",
                                  "modes": ["walking", "walking"], "startTime": "9:00"},
                  "origin": {"longitude": 200, "latitude": 32}
                }
                """));
        assertTrue(errors.stream().anyMatch(e -> "string_pattern_mismatch".equals(e.get("type"))
                && List.of("body", "requestId").equals(e.get("loc"))));
        assertTrue(errors.stream().anyMatch(e -> "less_than_equal".equals(e.get("type"))
                && List.of("body", "constraints", "days").equals(e.get("loc"))));
        assertTrue(errors.stream().anyMatch(e -> "greater_than_equal".equals(e.get("type"))
                && List.of("body", "constraints", "dailyMinutes").equals(e.get("loc"))));
        assertTrue(errors.stream().anyMatch(e -> "literal_error".equals(e.get("type"))
                && List.of("body", "constraints", "pace").equals(e.get("loc"))));
        assertTrue(errors.stream().anyMatch(e -> "date_parsing".equals(e.get("type"))
                && List.of("body", "constraints", "date").equals(e.get("loc"))));
        assertTrue(errors.stream().anyMatch(e -> "value_error".equals(e.get("type"))
                && List.of("body", "constraints", "modes", 1).equals(e.get("loc"))));
        assertTrue(errors.stream().anyMatch(e -> "string_pattern_mismatch".equals(e.get("type"))
                && List.of("body", "constraints", "startTime").equals(e.get("loc"))));
        assertTrue(errors.stream().anyMatch(e -> "less_than_equal".equals(e.get("type"))
                && List.of("body", "origin", "longitude").equals(e.get("loc"))));
    }

    @Test
    void rejectsOtherRoleAndDuplicateCurrentRoute() throws Exception {
        List<Map<String, Object>> errors = validator.validate(parse("""
                {
                  "requestId": "req-1",
                  "memoryId": "mem-1",
                  "accountId": 1,
                  "message": "研学",
                  "history": [{"role": "system", "content": "x"}],
                  "currentRoute": [{"poiId": "P1"}, {"poiId": "P1"}]
                }
                """));
        assertTrue(errors.stream().anyMatch(e -> "literal_error".equals(e.get("type"))
                && List.of("body", "history", 0, "role").equals(e.get("loc"))));
        assertFalse(errors.stream().filter(e -> "value_error".equals(e.get("type"))
                && List.of("body", "currentRoute").equals(e.get("loc"))).toList().isEmpty());
    }

    @Test
    void rejectsTooLongMessageAfterTrim() throws Exception {
        String message = "研".repeat(4001);
        PlanRequest request = parse("""
                {"requestId": "req-1", "memoryId": "mem-1", "accountId": 1, "message": "%s"}
                """.formatted(message));
        List<Map<String, Object>> errors = validator.validate(request);
        assertEquals(1, errors.size());
        assertEquals("string_too_long", errors.get(0).get("type"));
    }

    @Test
    void rejectsMemoryIdLongerThanBackendSessionKeyLimit() throws Exception {
        String memoryId = "m".repeat(129);
        List<Map<String, Object>> errors = validator.validate(parse("""
                {"requestId": "req-1", "memoryId": "%s", "accountId": 1, "message": "研学"}
                """.formatted(memoryId)));
        assertEquals(1, errors.size());
        assertEquals("string_too_long", errors.get(0).get("type"));
        assertEquals(List.of("body", "memoryId"), errors.get(0).get("loc"));
        assertEquals(Map.of("max_length", 128), errors.get(0).get("ctx"));
        // 128 位仍然接受
        assertTrue(validator.validate(parse("""
                {"requestId": "req-1", "memoryId": "%s", "accountId": 1, "message": "研学"}
                """.formatted("m".repeat(128)))).isEmpty());
    }
}
