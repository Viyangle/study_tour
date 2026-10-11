package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.dto.Constraints;
import com.viyangle.study_tour.agent.dto.CurrentStop;
import com.viyangle.study_tour.agent.dto.PlanRequest;
import com.viyangle.study_tour.agent.dto.Turn;

import java.util.ArrayList;
import java.util.List;

/**
 * 把结构化请求转换成既有规划服务使用的文本输入。
 *
 * <p>现有 {@code AiRoutePlanningService} 只接受一条用户消息，因此这里把结构化约束、
 * 当前路线、历史对话和本轮需求拼成一段文本。这是既有规划服务与 Agent 契约之间的适配，
 * 不是把约束“形式化验证”。</p>
 */
public final class PlannerInputBuilder {

    private static final int MAX_HISTORY_TURNS = 12;
    private static final int MAX_HISTORY_CHARS = 600;

    private PlannerInputBuilder() {
    }

    public static String build(PlanRequest request) {
        List<String> lines = new ArrayList<>();
        Constraints c = request.getConstraints();
        if (c != null) {
            List<String> parts = new ArrayList<>();
            addIfPresent(parts, "城市", c.getCity());
            addIfPresent(parts, "出行日期", c.getDate());
            if (c.getDays() != null && c.getDays() > 1) {
                parts.add("天数：" + c.getDays() + " 天");
            }
            if (c.getDailyMinutes() != null) {
                parts.add("每天可用时长：" + c.getDailyMinutes() + " 分钟");
            }
            addIfPresent(parts, "每天开始时间", c.getStartTime());
            addIfPresent(parts, "每天最晚结束时间", c.getFinishByTime());
            if (c.getBudget() != null) {
                parts.add("每人预算：" + trimNumber(c.getBudget()) + " 元");
            }
            if (c.getMaxWalkingMeters() != null) {
                parts.add("每天步行上限：" + c.getMaxWalkingMeters() + " 米");
            }
            if (c.getModes() != null && !c.getModes().isEmpty()) {
                parts.add("可用交通方式：" + String.join("、", modesInChinese(c.getModes())));
            }
            if (c.getMustVisit() != null && !c.getMustVisit().isEmpty()) {
                parts.add("必须去：" + String.join("、", c.getMustVisit()));
            }
            if (c.getAvoid() != null && !c.getAvoid().isEmpty()) {
                parts.add("不要去" + String.join("、不要去", c.getAvoid()));
            }
            if (c.getInterests() != null && !c.getInterests().isEmpty()) {
                parts.add("兴趣方向：" + String.join("、", c.getInterests()));
            }
            addIfPresent(parts, "同行人", c.getGroup());
            addIfPresent(parts, "无障碍需求", c.getAccessibility());
            if (c.getPace() != null && !"balanced".equals(c.getPace())) {
                parts.add("节奏：" + ("relaxed".equals(c.getPace()) ? "轻松" : "紧凑"));
            }
            if (Boolean.TRUE.equals(c.getReturnToStart())) {
                parts.add("需要返回每天出发点");
            }
            if (!parts.isEmpty()) {
                lines.add("【约束】" + String.join("；", parts));
            }
        }
        if (request.getOrigin() != null
                && request.getOrigin().getLongitude() != null && request.getOrigin().getLatitude() != null) {
            lines.add("【定位】用户当前位置：" + request.getOrigin().getLongitude() + ","
                    + request.getOrigin().getLatitude());
        }
        List<CurrentStop> currentRoute = request.currentRouteOrEmpty();
        if (!currentRoute.isEmpty()) {
            List<String> stops = new ArrayList<>();
            for (CurrentStop stop : currentRoute) {
                if (stop == null || stop.getPoiId() == null) {
                    continue;
                }
                String name = stop.getName() == null || stop.getName().isBlank() ? "" : "(" + stop.getName() + ")";
                stops.add(stop.getPoiId() + name);
            }
            if (!stops.isEmpty()) {
                lines.add("【当前路线】按顺序：" + String.join(" -> ", stops));
            }
        }
        List<Turn> history = request.historyOrEmpty();
        if (!history.isEmpty()) {
            List<String> turns = new ArrayList<>();
            int from = Math.max(0, history.size() - MAX_HISTORY_TURNS);
            for (int i = from; i < history.size(); i++) {
                Turn turn = history.get(i);
                if (turn == null || turn.getContent() == null) {
                    continue;
                }
                String content = turn.getContent();
                if (content.length() > MAX_HISTORY_CHARS) {
                    content = content.substring(0, MAX_HISTORY_CHARS);
                }
                turns.add(("assistant".equals(turn.getRole()) ? "助手" : "用户") + "：" + content);
            }
            if (!turns.isEmpty()) {
                lines.add("【历史对话】" + String.join(" | ", turns));
            }
        }
        lines.add("【本轮需求】" + request.getMessage().trim());
        return String.join("\n", lines);
    }

    private static List<String> modesInChinese(List<String> modes) {
        List<String> result = new ArrayList<>();
        for (String mode : modes) {
            if ("walking".equals(mode)) {
                result.add("步行");
            } else if ("transit".equals(mode)) {
                result.add("公共交通");
            } else if ("driving".equals(mode)) {
                result.add("驾车");
            }
        }
        return result;
    }

    private static void addIfPresent(List<String> parts, String label, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(label + "：" + value);
        }
    }

    private static String trimNumber(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
