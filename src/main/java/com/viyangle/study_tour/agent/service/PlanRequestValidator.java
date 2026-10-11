package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.dto.Constraints;
import com.viyangle.study_tour.agent.dto.CurrentStop;
import com.viyangle.study_tour.agent.dto.Origin;
import com.viyangle.study_tour.agent.dto.PlanRequest;
import com.viyangle.study_tour.agent.dto.Turn;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 手写校验器，按 {@code docs/openapi.json} 的 PlanRequest / Constraints / Turn / CurrentStop / Origin
 * 约束逐项检查，并生成 FastAPI 风格的 422 detail 条目。
 *
 * <p>之所以不使用 Bean Validation：项目中未引入 {@code spring-boot-starter-validation}，
 * 且需要精确控制 {@code type}/{@code loc}/{@code msg}/{@code ctx} 结构。</p>
 */
@Component
public class PlanRequestValidator {

    static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");
    static final Pattern MEMORY_ID = Pattern.compile("^[A-Za-z0-9_-]{1,160}$");
    static final Pattern POI_ID = Pattern.compile("^[A-Za-z0-9_-]{1,80}$");
    static final Pattern CLOCK = Pattern.compile("^(?:[01]\\d|2[0-3]):[0-5]\\d$");
    static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    /** 与既有 {@code AiConversationScope} 的 memoryId 上限一致，避免通过校验后在后台执行时才失败。 */
    static final int MEMORY_ID_MAX_LENGTH = 128;

    private static final Set<String> ROLES = Set.of("user", "assistant");
    private static final Set<String> MODES = Set.of("walking", "transit", "driving");
    private static final Set<String> PACES = Set.of("relaxed", "balanced", "intensive");

    /** 校验整个请求体，返回全部错误；空列表表示通过。 */
    public List<Map<String, Object>> validate(PlanRequest body) {
        List<Map<String, Object>> errors = new ArrayList<>();
        if (body == null) {
            errors.add(error("missing", loc("body"), "Field required", null, null));
            return errors;
        }
        extraFields(errors, body.getExtraFields(), loc("body"));

        required(errors, loc("body"), "requestId", body.getRequestId());
        pattern(errors, loc("body"), "requestId", REQUEST_ID, body.getRequestId());
        required(errors, loc("body"), "memoryId", body.getMemoryId());
        pattern(errors, loc("body"), "memoryId", MEMORY_ID, body.getMemoryId());
        if (body.getMemoryId() != null && MEMORY_ID.matcher(body.getMemoryId()).matches()
                && body.getMemoryId().length() > MEMORY_ID_MAX_LENGTH) {
            errors.add(error("string_too_long", loc("body", "memoryId"),
                    "String should have at most " + MEMORY_ID_MAX_LENGTH + " characters", body.getMemoryId(),
                    Map.of("max_length", MEMORY_ID_MAX_LENGTH)));
        }

        required(errors, loc("body"), "accountId", body.getAccountId());
        if (body.getAccountId() != null && body.getAccountId() <= 0) {
            errors.add(error("greater_than", loc("body", "accountId"),
                    "Input should be greater than 0", body.getAccountId(), Map.of("gt", 0)));
        }

        required(errors, loc("body"), "message", body.getMessage());
        if (body.getMessage() != null) {
            String trimmed = body.getMessage().trim();
            if (trimmed.isEmpty()) {
                errors.add(error("string_too_short", loc("body", "message"),
                        "String should have at least 1 character", body.getMessage(),
                        Map.of("min_length", 1)));
            } else if (trimmed.length() > 4000) {
                errors.add(error("string_too_long", loc("body", "message"),
                        "String should have at most 4000 characters", body.getMessage(),
                        Map.of("max_length", 4000)));
            }
        }

        validateHistory(errors, body.getHistory());
        validateCurrentRoute(errors, body.getCurrentRoute());
        validateConstraints(errors, body.getConstraints());
        validateOrigin(errors, body.getOrigin());
        return errors;
    }

    private void validateHistory(List<Map<String, Object>> errors, List<Turn> history) {
        if (history == null) {
            return;
        }
        if (history.size() > 24) {
            errors.add(error("too_long", loc("body", "history"),
                    "List should have at most 24 items after validation, not " + history.size(),
                    null, Map.of("max_length", 24)));
        }
        for (int i = 0; i < history.size(); i++) {
            Turn turn = history.get(i);
            List<Object> itemLoc = loc("body", "history", i);
            if (turn == null) {
                errors.add(error("value_error", itemLoc, "Value error, 对话记录不能为空", null, null));
                continue;
            }
            extraFields(errors, turn.getExtraFields(), itemLoc);
            required(errors, itemLoc, "role", turn.getRole());
            if (turn.getRole() != null && !ROLES.contains(turn.getRole())) {
                errors.add(error("literal_error", append(itemLoc, "role"),
                        "Input should be 'user' or 'assistant'", turn.getRole(),
                        Map.of("expected", "'user' or 'assistant'")));
            }
            required(errors, itemLoc, "content", turn.getContent());
            stringRange(errors, append(itemLoc, "content"), turn.getContent(), 1, 8000);
        }
    }

    private void validateCurrentRoute(List<Map<String, Object>> errors, List<CurrentStop> route) {
        if (route == null) {
            return;
        }
        if (route.size() > 20) {
            errors.add(error("too_long", loc("body", "currentRoute"),
                    "List should have at most 20 items after validation, not " + route.size(),
                    null, Map.of("max_length", 20)));
        }
        Set<String> seen = new HashSet<>();
        List<String> duplicated = new ArrayList<>();
        for (int i = 0; i < route.size(); i++) {
            CurrentStop stop = route.get(i);
            List<Object> itemLoc = loc("body", "currentRoute", i);
            if (stop == null) {
                errors.add(error("value_error", itemLoc, "Value error, 当前路线地点不能为空", null, null));
                continue;
            }
            required(errors, itemLoc, "poiId", stop.getPoiId());
            pattern(errors, itemLoc, "poiId", POI_ID, stop.getPoiId());
            if (stop.getPoiId() != null && !stop.getPoiId().isBlank() && !seen.add(stop.getPoiId())) {
                duplicated.add(stop.getPoiId());
            }
            stringRange(errors, append(itemLoc, "name"), stop.getName(), null, 160);
            stringRange(errors, append(itemLoc, "notes"), stop.getNotes(), null, 2000);
            Integer duration = stop.getRecommendedDuration();
            if (duration != null && (duration < 0 || duration > 960)) {
                errors.add(rangeError(append(itemLoc, "recommendedDuration"), duration, 0, 960));
            }
        }
        if (!duplicated.isEmpty()) {
            errors.add(error("value_error", loc("body", "currentRoute"),
                    "Value error, currentRoute 中 poiId 不能重复: " + String.join(", ", duplicated),
                    null, null));
        }
    }

    private void validateConstraints(List<Map<String, Object>> errors, Constraints c) {
        if (c == null) {
            return;
        }
        List<Object> base = loc("body", "constraints");
        extraFields(errors, c.getExtraFields(), base);
        stringRange(errors, append(base, "city"), c.getCity(), null, 80);
        if (c.getDate() != null && !validDate(c.getDate())) {
            errors.add(error("date_parsing", append(base, "date"),
                    "Input should be a valid date or datetime, invalid date format, expected YYYY-MM-DD",
                    c.getDate(), null));
        }
        rangeErrorIf(errors, append(base, "days"), c.getDays(), 1, 7);
        rangeErrorIf(errors, append(base, "dailyMinutes"), c.getDailyMinutes(), 30, 960);
        pattern(errors, base, "startTime", CLOCK, c.getStartTime());
        pattern(errors, base, "finishByTime", CLOCK, c.getFinishByTime());
        if (c.getBudget() != null) {
            List<Object> budgetLoc = append(base, "budget");
            double budget = c.getBudget();
            if (Double.isNaN(budget) || Double.isInfinite(budget)) {
                errors.add(error("finite_number", budgetLoc, "Input should be a finite number", c.getBudget(), null));
            } else if (budget < 0 || budget > 1000000) {
                errors.add(rangeError(budgetLoc, c.getBudget(), 0, 1000000));
            }
        }
        rangeErrorIf(errors, append(base, "maxWalkingMeters"), c.getMaxWalkingMeters(), 0, 100000);
        validateModes(errors, c.getModes());
        validateStringList(errors, "mustVisit", c.getMustVisit(), 20);
        validateStringList(errors, "avoid", c.getAvoid(), 20);
        validateStringList(errors, "interests", c.getInterests(), 12);
        stringRange(errors, append(base, "group"), c.getGroup(), null, 300);
        stringRange(errors, append(base, "accessibility"), c.getAccessibility(), null, 300);
        if (c.getPace() != null && !PACES.contains(c.getPace())) {
            errors.add(error("literal_error", append(base, "pace"),
                    "Input should be 'relaxed', 'balanced' or 'intensive'", c.getPace(),
                    Map.of("expected", "'relaxed', 'balanced' or 'intensive'")));
        }
    }

    private void validateModes(List<Map<String, Object>> errors, List<String> modes) {
        if (modes == null) {
            return;
        }
        List<Object> base = loc("body", "constraints", "modes");
        if (modes.isEmpty()) {
            errors.add(error("too_short", base,
                    "List should have at least 1 item after validation, not 0", null,
                    Map.of("min_length", 1)));
        }
        if (modes.size() > 3) {
            errors.add(error("too_long", base,
                    "List should have at most 3 items after validation, not " + modes.size(), null,
                    Map.of("max_length", 3)));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < modes.size(); i++) {
            String mode = modes.get(i);
            if (mode == null || !MODES.contains(mode)) {
                errors.add(error("literal_error", append(base, i),
                        "Input should be 'walking', 'transit' or 'driving'", mode,
                        Map.of("expected", "'walking', 'transit' or 'driving'")));
            } else if (!seen.add(mode)) {
                errors.add(error("value_error", append(base, i),
                        "Value error, 交通方式不能重复: " + mode, mode, null));
            }
        }
    }

    private void validateStringList(List<Map<String, Object>> errors, String field,
                                    List<String> values, int maxItems) {
        if (values == null) {
            return;
        }
        List<Object> base = loc("body", "constraints", field);
        if (values.size() > maxItems) {
            errors.add(error("too_long", base,
                    "List should have at most " + maxItems + " items after validation, not " + values.size(),
                    null, Map.of("max_length", maxItems)));
        }
        for (int i = 0; i < values.size(); i++) {
            stringRange(errors, append(base, i), values.get(i), 1, 160);
        }
    }

    private void validateOrigin(List<Map<String, Object>> errors, Origin origin) {
        if (origin == null) {
            return;
        }
        List<Object> base = loc("body", "origin");
        extraFields(errors, origin.getExtraFields(), base);
        required(errors, base, "longitude", origin.getLongitude());
        required(errors, base, "latitude", origin.getLatitude());
        Double longitude = origin.getLongitude();
        Double latitude = origin.getLatitude();
        if (longitude != null && (longitude < 73 || longitude > 136)) {
            errors.add(rangeError(append(base, "longitude"), longitude, 73, 136));
        }
        if (latitude != null && (latitude < 3 || latitude > 54)) {
            errors.add(rangeError(append(base, "latitude"), latitude, 3, 54));
        }
    }

    private boolean validDate(String value) {
        if (!DATE.matcher(value).matches()) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private void extraFields(List<Map<String, Object>> errors, Map<String, Object> extra, List<Object> base) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        extra.forEach((name, value) -> errors.add(error("extra_forbidden", append(base, name),
                "Extra inputs are not permitted", value, null)));
    }

    private void required(List<Map<String, Object>> errors, List<Object> base, String field, Object value) {
        if (value == null) {
            errors.add(error("missing", append(base, field), "Field required", null, null));
        }
    }

    private void pattern(List<Map<String, Object>> errors, List<Object> base, String field,
                         Pattern pattern, String value) {
        if (value == null) {
            return;
        }
        if (!pattern.matcher(value).matches()) {
            errors.add(error("string_pattern_mismatch", append(base, field),
                    "String should match pattern '" + pattern.pattern() + "'", value,
                    Map.of("pattern", pattern.pattern())));
        }
    }

    private void stringRange(List<Map<String, Object>> errors, List<Object> location, String value,
                             Integer min, Integer max) {
        if (value == null) {
            return;
        }
        if (min != null && value.length() < min) {
            errors.add(error("string_too_short", location,
                    "String should have at least " + min + (min == 1 ? " character" : " characters"), value,
                    Map.of("min_length", min)));
        }
        if (max != null && value.length() > max) {
            errors.add(error("string_too_long", location,
                    "String should have at most " + max + " characters", value,
                    Map.of("max_length", max)));
        }
    }

    private void rangeErrorIf(List<Map<String, Object>> errors, List<Object> location,
                              Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            errors.add(rangeError(location, value, min, max));
        }
    }

    private Map<String, Object> rangeError(List<Object> location, Number value, double min, double max) {
        if (value.doubleValue() < min) {
            return error("greater_than_equal", location,
                    "Input should be greater than or equal to " + trimNumber(min), value,
                    Map.of("ge", min));
        }
        return error("less_than_equal", location,
                "Input should be less than or equal to " + trimNumber(max), value,
                Map.of("le", max));
    }

    private static String trimNumber(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static List<Object> loc(Object... parts) {
        return new ArrayList<>(Arrays.asList(parts));
    }

    private static List<Object> append(List<Object> base, Object extra) {
        List<Object> result = new ArrayList<>(base);
        result.add(extra);
        return result;
    }

    private static Map<String, Object> error(String type, List<Object> location, String msg,
                                            Object input, Map<String, ?> ctx) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("loc", new ArrayList<>(location));
        entry.put("msg", msg);
        entry.put("input", input);
        if (ctx != null) {
            entry.put("ctx", ctx);
        }
        return entry;
    }
}
