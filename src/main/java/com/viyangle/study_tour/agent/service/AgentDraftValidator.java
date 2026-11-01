package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.dto.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 只检查数据结构、数值及引用合法性；不判断模型规划的业务可行性。 */
public final class AgentDraftValidator {
    private AgentDraftValidator() {}

    public static void validateLegs(List<Leg> legs, Set<String> poiIds, boolean hasOrigin) {
        require(legs != null && legs.size() <= 100, "交通段必须为数组且不超过 100 项");
        Set<String> keys = new HashSet<>();
        for (Leg leg : legs) {
            require(leg != null, "交通段不能为 null");
            day(leg.getDay());
            require(poiIds.contains(leg.getOrigin()) || hasOrigin && "__origin__".equals(leg.getOrigin()), "交通起点未在当前路线中");
            require(poiIds.contains(leg.getDestination()) || hasOrigin && "__origin__".equals(leg.getDestination()), "交通终点未在当前路线中");
            require(!leg.getOrigin().equals(leg.getDestination()), "交通起终点不能相同");
            require(Set.of("walking", "transit", "driving").contains(text(leg.getMode())), "无效交通方式");
            nonNegative(leg.getMinutes(), "minutes");
            nonNegative(leg.getDistanceMeters(), "distanceMeters");
            nonNegative(leg.getWalkingMeters(), "walkingMeters");
            money(leg.getCost(), "cost");
            require(keys.add(leg.getDay() + "|" + leg.getOrigin() + "|" + leg.getDestination()), "交通段重复");
            if (leg.getPolyline() != null) {
                require(leg.getPolyline().size() <= 2000, "轨迹点过多");
                for (List<Double> point : leg.getPolyline()) {
                    require(point != null && point.size() == 2, "轨迹点必须是 [经度,纬度]");
                    coordinate(point.get(0), point.get(1));
                }
            }
            if (leg.getInstructions() != null) require(leg.getInstructions().size() <= 100, "交通指引过多");
        }
    }

    public static void validateDays(List<DaySummary> days, Set<String> poiIds) {
        require(days != null && days.size() <= 7, "日程必须为数组且不超过 7 天");
        Set<Integer> numbers = new HashSet<>();
        Set<String> visited = new HashSet<>();
        int previousDay = 0;
        for (DaySummary day : days) {
            require(day != null, "日程不能为 null");
            day(day.getDay());
            require(numbers.add(day.getDay()) && day.getDay() > previousDay, "天数必须递增且不重复");
            previousDay = day.getDay();
            if (day.getDate() != null && !day.getDate().isBlank()) {
                try { LocalDate.parse(day.getDate()); }
                catch (DateTimeParseException e) { throw new IllegalArgumentException("日期必须为 yyyy-MM-dd"); }
            }
            require(day.getSchedule() != null && day.getSchedule().size() <= 100, "每天日程项不得超过 100 个");
            int previousDeparture = -1;
            for (ScheduleItem item : day.getSchedule()) {
                require(item != null, "日程项不能为 null");
                int arrival = clock(item.getArrival());
                int departure = clock(item.getDeparture());
                require(departure >= arrival && arrival >= previousDeparture, "日程时间必须有序且不重叠");
                previousDeparture = departure;
                require(item.getMinutes() != null, "日程项 minutes 必填");
                nonNegative(item.getMinutes(), "minutes");
                if ("break".equals(item.getType())) {
                    require(item.getPoiId() == null, "休息项不能引用景点");
                } else {
                    require(item.getType() == null || "visit".equals(item.getType()), "无效日程项类型");
                    require(poiIds.contains(item.getPoiId()), "日程景点未在当前路线中");
                    require(visited.add(item.getPoiId()), "同一景点不能重复安排");
                }
            }
            nonNegative(day.getTotalMinutes(), "totalMinutes");
            nonNegative(day.getWalkingMeters(), "walkingMeters");
            nonNegative(day.getLegCount(), "legCount");
            money(day.getEstimatedCost(), "estimatedCost");
            if (day.getFinishTime() != null) clock(day.getFinishTime());
        }
    }

    public static void validateComplete(AgentPlan plan, boolean hasOrigin) {
        require(plan.getNodes() != null && !plan.getNodes().isEmpty(), "请先通过 edit_route 提供路线");
        Set<String> ids = new HashSet<>();
        for (RouteNode node : plan.getNodes()) {
            require(node != null && node.getPoiId() != null && ids.add(node.getPoiId()), "路线 POI 引用不能为空或重复");
            require(node.getRecommendedDuration() != null && node.getRecommendedDuration() >= 1
                    && node.getRecommendedDuration() <= 1440, "站点 recommendedDuration 必须在 1–1440 分钟之间");
        }
        validateLegs(plan.getLegs(), ids, hasOrigin);
        validateDays(plan.getDays(), ids);
        Set<String> scheduled = new HashSet<>();
        plan.getDays().forEach(day -> day.getSchedule().forEach(item -> {
            if (item.getPoiId() != null) scheduled.add(item.getPoiId());
        }));
        require(scheduled.equals(ids), "请通过 edit_schedule 为当前路线中的每个景点提供日程");
        // 仅检查跨对象引用一致性，不检查交通是否赶得上、费用或步行是否超限。
        for (Leg leg : plan.getLegs()) {
            DaySummary day = plan.getDays().stream().filter(d -> d.getDay().equals(leg.getDay())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("交通段引用了不存在的天数"));
            Set<String> dayIds = new HashSet<>();
            day.getSchedule().forEach(item -> { if (item.getPoiId() != null) dayIds.add(item.getPoiId()); });
            require("__origin__".equals(leg.getOrigin()) || dayIds.contains(leg.getOrigin()), "交通起点不在对应日期的路线中");
            require("__origin__".equals(leg.getDestination()) || dayIds.contains(leg.getDestination()), "交通终点不在对应日期的路线中");
        }
    }

    static void day(Integer day) { require(day != null && day >= 1 && day <= 7, "day 必须在 1–7 之间"); }
    static void nonNegative(Integer value, String name) { require(value == null || value >= 0, name + " 不能为负数"); }
    static void money(Double value, String name) { require(value == null || Double.isFinite(value) && value >= 0, name + " 必须为非负有限数"); }
    static String text(String value) { return value == null ? "" : value; }
    static int clock(String value) {
        require(value != null && value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d"), "时间必须为 HH:mm");
        return LocalTime.parse(value).toSecondOfDay() / 60;
    }
    static void coordinate(Double longitude, Double latitude) {
        require(longitude != null && latitude != null && Double.isFinite(longitude) && Double.isFinite(latitude)
                && longitude >= -180 && longitude <= 180 && latitude >= -90 && latitude <= 90, "无效经纬度");
    }
    static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
