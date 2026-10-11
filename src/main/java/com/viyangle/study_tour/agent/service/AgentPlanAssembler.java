package com.viyangle.study_tour.agent.service;

import com.viyangle.study_tour.agent.config.AgentProperties;
import com.viyangle.study_tour.agent.dto.AgentPlan;
import com.viyangle.study_tour.agent.dto.Clarification;
import com.viyangle.study_tour.agent.dto.Constraints;
import com.viyangle.study_tour.agent.dto.CurrentStop;
import com.viyangle.study_tour.agent.dto.DaySummary;
import com.viyangle.study_tour.agent.dto.Leg;
import com.viyangle.study_tour.agent.dto.PlanRequest;
import com.viyangle.study_tour.agent.dto.RouteNode;
import com.viyangle.study_tour.agent.dto.ScheduleItem;
import com.viyangle.study_tour.agent.dto.ToolEvent;
import com.viyangle.study_tour.agent.exception.AgentRunCancelledException;
import com.viyangle.study_tour.agent.exception.AgentPlanningException;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.AIRouteItem;
import com.viyangle.study_tour.pojo.AIRoutePlan;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.utils.AmapDirectionClient;
import com.viyangle.study_tour.utils.AmapTransitClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 把既有规划服务返回的 poiId 列表装配成文档要求的 {@code AgentPlan}。
 *
 * <p>这里不调用模型：地点详情来自数据库与高德 place/detail，交通段来自高德路径规划，
 * 时间、步行与费用汇总按固定规则计算。任何无法核实的数据都会进入 {@code warnings}，
 * 不会被当成已知值。</p>
 */
@Slf4j
@Component
public class AgentPlanAssembler {

    private static final int LEG_BUFFER_MINUTES = 5;
    private static final int BREAK_MINUTES = 20;
    private static final int WALK_LIMIT_METERS = 1500;
    private static final int MIN_VISIT_MINUTES = 10;
    private static final int MAX_VISIT_MINUTES = 480;
    private static final double WALK_METERS_PER_MINUTE = 75.0;
    private static final double TRANSIT_METERS_PER_MINUTE = 250.0;
    private static final double DRIVING_METERS_PER_MINUTE = 400.0;
    private static final int MAX_QUESTIONS = 3;
    private static final List<String> COMMUTE_KEYWORDS =
            List.of("前往", "乘坐", "步行", "换乘", "打车", "公交", "地铁");

    private final AttractionMapper attractionMapper;
    private final AmapTransitClient amapTransitClient;
    private final AmapDirectionClient amapDirectionClient;
    private final AgentProperties props;

    public AgentPlanAssembler(AttractionMapper attractionMapper,
                              AmapTransitClient amapTransitClient,
                              AmapDirectionClient amapDirectionClient,
                              AgentProperties props) {
        this.attractionMapper = attractionMapper;
        this.amapTransitClient = amapTransitClient;
        this.amapDirectionClient = amapDirectionClient;
        this.props = props;
    }

    /** 装配结果：要么是可应用的方案，要么是追问。 */
    public static final class Outcome {
        private final AgentPlan plan;
        private final Clarification clarification;

        private Outcome(AgentPlan plan, Clarification clarification) {
            this.plan = plan;
            this.clarification = clarification;
        }

        public static Outcome ofPlan(AgentPlan plan) {
            return new Outcome(plan, null);
        }

        public static Outcome ofClarification(Clarification clarification) {
            return new Outcome(null, clarification);
        }

        public AgentPlan getPlan() {
            return plan;
        }

        public Clarification getClarification() {
            return clarification;
        }
    }

    public Outcome assemble(PlanRequest request, AIRoutePlan aiPlan,
                            Consumer<ToolEvent> events, BooleanSupplier cancelled) {
        Constraints constraints = ConstraintDefaults.apply(request.getConstraints());
        List<AIRouteItem> items = orderedItems(aiPlan);
        if (items.isEmpty()) {
            throw new AgentPlanningException("模型未返回任何地点，无法生成方案");
        }

        long detailsStartedAt = System.nanoTime();
        events.accept(ToolEvent.of("get_place_details", "核实地点详情", "running", null));
        DirectionBudget budget = new DirectionBudget(props.getMaxDirectionRequests());
        Resolved resolved = resolveNodes(items, budget);
        events.accept(ToolEvent.of("get_place_details", "核实地点详情",
                resolved.unresolved.isEmpty() ? "completed" : "needs_revision",
                elapsedMs(detailsStartedAt)));
        if (resolved.nodes.isEmpty()) {
            throw new AgentPlanningException("无法核实任何地点详情，请检查高德服务配置或稍后重试");
        }

        List<List<PlannedNode>> groups = splitIntoDays(resolved.nodes, constraints);
        List<String> conflicts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Leg> allLegs = new ArrayList<>();
        List<DaySummary> days = new ArrayList<>();
        if (!resolved.unresolved.isEmpty()) {
            warnings.add("有 " + resolved.unresolved.size() + " 个地点未能核实，已从方案中移除："
                    + String.join("、", resolved.unresolved));
        }

        long legStartedAt = System.nanoTime();
        events.accept(ToolEvent.of("calculate_leg", "计算交通段", "running", null));
        int fallbackLegs = 0;
        int missingWalkingLegs = 0;
        boolean forcedNonWalking = false;
        List<String> dayDates = dayDates(constraints, groups.size());
        for (int i = 0; i < groups.size(); i++) {
            checkCancelled(cancelled);
            DayBuild build = buildDay(i + 1, dayDates.get(i), groups.get(i), constraints, budget);
            if (build.walkingLimitExceeded && constraints.getMaxWalkingMeters() != null) {
                DayBuild retry = buildDay(i + 1, dayDates.get(i), groups.get(i), constraints, budget, true);
                if (!retry.walkingLimitExceeded) {
                    forcedNonWalking = true;
                    build = retry;
                }
            }
            fallbackLegs += build.fallbackLegs;
            missingWalkingLegs += build.missingWalkingLegs;
            conflicts.addAll(build.conflicts);
            allLegs.addAll(build.legs);
            days.add(build.summary);
            applyVisitTimes(build.summary, groups.get(i));
        }
        boolean legDegraded = fallbackLegs > 0 || missingWalkingLegs > 0;
        events.accept(ToolEvent.of("calculate_leg", "计算交通段",
                legDegraded ? "needs_revision" : "completed", elapsedMs(legStartedAt)));

        long buildStartedAt = System.nanoTime();
        events.accept(ToolEvent.of("build_itinerary", "校验行程与约束", "running", null));
        conflicts.addAll(mustVisitConflicts(constraints, resolved.nodes));
        events.accept(ToolEvent.of("build_itinerary", "校验行程与约束",
                conflicts.isEmpty() ? "completed" : "needs_revision", elapsedMs(buildStartedAt)));

        if (!conflicts.isEmpty()) {
            events.accept(ToolEvent.of("ask_user", "等待你补充需求", "completed", null));
            return Outcome.ofClarification(clarification(constraints, conflicts));
        }

        AgentPlan plan = new AgentPlan();
        plan.setConstraints(constraints);
        plan.setNodes(resolved.nodes.stream().map(node -> node.node).toList());
        plan.setDays(days);
        plan.setLegs(allLegs);
        plan.setMessage(buildMessage(aiPlan, resolved.nodes, days.size()));
        plan.setAssumptions(assumptions(constraints, dayDates, forcedNonWalking));
        int unknownCosts = resolved.nodes.size() + unknownLegCosts(allLegs);
        plan.setWarnings(warnings(constraints, warnings, fallbackLegs, missingWalkingLegs, unknownCosts));
        plan.setEstimatedCost(round2(days.stream().mapToDouble(DaySummary::getEstimatedCost).sum()));
        plan.setUnknownCosts(unknownCosts);
        Diff diff = diff(request.currentRouteOrEmpty(), resolved.nodes);
        plan.setAddedPoiIds(diff.added);
        plan.setRemovedPoiIds(diff.removed);
        events.accept(ToolEvent.of("finish_plan", "方案已完成", "completed", null));
        return Outcome.ofPlan(plan);
    }

    /** 路线增删相对请求中的当前路线。 */
    private record Diff(List<String> added, List<String> removed) {
    }

    private static final class PlannedNode {
        private final RouteNode node;
        private final String location;
        private final int minutes;
        private final String note;

        private PlannedNode(RouteNode node, String location, int minutes, String note) {
            this.node = node;
            this.location = location;
            this.minutes = minutes;
            this.note = note;
        }
    }

    private record Resolved(List<PlannedNode> nodes, List<String> unresolved) {
    }

    private static final class DayBuild {
        private DaySummary summary;
        private List<Leg> legs = new ArrayList<>();
        private List<String> conflicts = new ArrayList<>();
        private int fallbackLegs;
        private int missingWalkingLegs;
        private boolean walkingLimitExceeded;
    }

    /** 单轮路径规划请求预算，避免一次规划发出过多高德请求。 */
    private static final class DirectionBudget {
        private int remaining;

        private DirectionBudget(int remaining) {
            this.remaining = remaining;
        }

        private boolean take() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }

    private List<AIRouteItem> orderedItems(AIRoutePlan aiPlan) {
        if (aiPlan == null || aiPlan.getItems() == null) {
            return List.of();
        }
        List<AIRouteItem> items = new ArrayList<>();
        for (AIRouteItem item : aiPlan.getItems()) {
            if (item != null && item.getPoiId() != null && !item.getPoiId().isBlank()) {
                items.add(item);
            }
        }
        items.sort(Comparator.comparingInt(item ->
                item.getVisitOrder() == null ? Integer.MAX_VALUE : item.getVisitOrder()));
        return items;
    }

    private Resolved resolveNodes(List<AIRouteItem> items, DirectionBudget budget) {
        Map<String, Attraction> fromDb = loadFromDatabase(items);
        List<PlannedNode> nodes = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        int order = 0;
        for (AIRouteItem item : items) {
            String poiId = item.getPoiId().trim();
            if (!used.add(poiId.toUpperCase(Locale.ROOT))) {
                continue;
            }
            Attraction attraction = fromDb.get(poiId.toUpperCase(Locale.ROOT));
            if (attraction == null && budget.take()) {
                attraction = amapTransitClient.fetchAttraction(poiId);
            }
            if (attraction == null || blank(attraction.getLocation())) {
                unresolved.add(poiId);
                continue;
            }
            order++;
            nodes.add(new PlannedNode(toRouteNode(attraction, item, order), attraction.getLocation(),
                    durationOf(item), item.getNotes()));
        }
        return new Resolved(nodes, unresolved);
    }

    private Map<String, Attraction> loadFromDatabase(List<AIRouteItem> items) {
        List<String> poiIds = items.stream().map(AIRouteItem::getPoiId).distinct().toList();
        Map<String, Attraction> result = new HashMap<>();
        try {
            List<Attraction> found = attractionMapper.selectByPoiIds(poiIds);
            if (found != null) {
                for (Attraction attraction : found) {
                    if (attraction != null && attraction.getPoiId() != null) {
                        result.put(attraction.getPoiId().toUpperCase(Locale.ROOT), attraction);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取景点详情失败，将改用高德补全: {}", e.getMessage());
        }
        return result;
    }

    private RouteNode toRouteNode(Attraction attraction, AIRouteItem item, int order) {
        RouteNode node = new RouteNode();
        node.setPoiId(attraction.getPoiId());
        node.setName(nonNull(attraction.getName()));
        node.setLocation(nonNull(attraction.getLocation()));
        node.setAddress(nonNull(attraction.getAddress()));
        node.setCityname(nonNull(attraction.getCityname()));
        node.setCitycode(nonNull(attraction.getCitycode()));
        node.setAdcode(nonNull(attraction.getAdcode()));
        node.setType(nonNull(attraction.getType()));
        node.setTypecode(nonNull(attraction.getTypecode()));
        node.setTel(nonNull(attraction.getTel()));
        node.setOpentimeToday(nonNull(attraction.getOpentimeToday()));
        node.setOpentimeWeek(nonNull(attraction.getOpentimeWeek()));
        node.setVisitOrder(order);
        node.setRecommendedDuration(clamp(durationOf(item), MIN_VISIT_MINUTES, MAX_VISIT_MINUTES));
        node.setNotes(nonNull(item.getNotes()));
        return node;
    }

    private List<List<PlannedNode>> splitIntoDays(List<PlannedNode> nodes, Constraints constraints) {
        int days = Math.max(1, Math.min(constraints.getDays() == null ? 1 : constraints.getDays(), 7));
        List<List<PlannedNode>> groups = new ArrayList<>();
        if (days == 1 || nodes.size() <= 1) {
            groups.add(new ArrayList<>(nodes));
            return groups;
        }
        int dailyMinutes = constraints.getDailyMinutes() == null ? 480 : constraints.getDailyMinutes();
        List<PlannedNode> current = new ArrayList<>();
        int used = 0;
        for (PlannedNode node : nodes) {
            int cost = node.minutes + LEG_BUFFER_MINUTES;
            boolean roomLeft = groups.size() < days - 1;
            if (!current.isEmpty() && roomLeft && used + cost > dailyMinutes) {
                groups.add(current);
                current = new ArrayList<>();
                used = 0;
            }
            current.add(node);
            used += cost;
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        while (groups.size() > days) {
            List<PlannedNode> last = groups.remove(groups.size() - 1);
            groups.get(groups.size() - 1).addAll(last);
        }
        return groups;
    }

    private DayBuild buildDay(int day, String date, List<PlannedNode> group,
                              Constraints constraints, DirectionBudget budget) {
        return buildDay(day, date, group, constraints, budget, false);
    }

    /**
     * 按最终采纳的日程回填 {@link RouteNode#getVisitTime()}。
     *
     * <p>docs/API.md 要求节点带 {@code visitTime}（“日期或第N天 + HH:mm”），
     * openapi.json 也把该字段列为必需。参观项的到达时间与节点顺序一致，
     * 休息项（{@code type=break}）不占用节点。</p>
     */
    private static void applyVisitTimes(DaySummary summary, List<PlannedNode> group) {
        String prefix = summary.getDate() == null || summary.getDate().isBlank()
                ? "第" + summary.getDay() + "天"
                : summary.getDate();
        int index = 0;
        for (ScheduleItem item : summary.getSchedule()) {
            if (item.getPoiId() == null || item.getPoiId().isBlank()) {
                continue;
            }
            if (index >= group.size() || item.getArrival() == null) {
                break;
            }
            group.get(index).node.setVisitTime(prefix + " " + item.getArrival());
            index++;
        }
    }

    private DayBuild buildDay(int day, String date, List<PlannedNode> group,
                              Constraints constraints, DirectionBudget budget, boolean avoidWalking) {
        DayBuild build = new DayBuild();
        int clock = parseClock(constraints.getStartTime(), 9 * 60);
        int startClock = clock;
        int walking = 0;
        int missingWalking = 0;
        double cost = 0.0;
        PlannedNode previous = null;
        List<ScheduleItem> schedule = new ArrayList<>();
        List<Leg> legs = new ArrayList<>();
        for (int i = 0; i < group.size(); i++) {
            PlannedNode current = group.get(i);
            if (previous != null) {
                Leg leg = buildLeg(day, previous, current, constraints, budget, avoidWalking);
                legs.add(leg);
                clock += (leg.getMinutes() == null ? 0 : leg.getMinutes()) + LEG_BUFFER_MINUTES;
                if ("直线估算".equals(leg.getSource())) {
                    build.fallbackLegs++;
                }
                if (leg.getWalkingMeters() == null) {
                    missingWalking++;
                } else {
                    walking += leg.getWalkingMeters();
                }
                if (leg.getCost() != null) {
                    cost += leg.getCost();
                }
            }
            int duration = clamp(current.minutes, MIN_VISIT_MINUTES, MAX_VISIT_MINUTES);
            int departure = clock + duration;
            schedule.add(ScheduleItem.visit(current.node.getPoiId(), current.node.getName(),
                    formatClock(clock), formatClock(departure), duration, learningGoal(current.note)));
            clock = departure;
            if (i == 1 && group.size() >= 3) {
                int breakStart = clock;
                clock += BREAK_MINUTES;
                schedule.add(ScheduleItem.rest(formatClock(breakStart), formatClock(clock), BREAK_MINUTES));
            }
            previous = current;
        }

        build.legs = legs;
        build.missingWalkingLegs = missingWalking;
        DaySummary summary = new DaySummary();
        summary.setDay(day);
        summary.setDate(date);
        summary.setSchedule(schedule);
        summary.setTotalMinutes(Math.max(0, clock - startClock));
        summary.setWalkingMeters(walking);
        summary.setEstimatedCost(round2(cost));
        summary.setLegCount(legs.size());
        summary.setFinishTime(formatClock(clock));
        build.summary = summary;

        Integer maxWalking = constraints.getMaxWalkingMeters();
        if (maxWalking != null && walking > maxWalking) {
            build.walkingLimitExceeded = true;
            if (avoidWalking) {
                build.conflicts.add("第 " + day + " 天步行约 " + walking + " 米，超过上限 " + maxWalking + " 米");
            }
        }
        int dailyMinutes = constraints.getDailyMinutes() == null ? 480 : constraints.getDailyMinutes();
        if (summary.getTotalMinutes() > dailyMinutes) {
            build.conflicts.add("第 " + day + " 天需要约 " + summary.getTotalMinutes()
                    + " 分钟，超过每天 " + dailyMinutes + " 分钟");
        }
        Integer finishBy = parseClockOrNull(constraints.getFinishByTime());
        if (finishBy != null && clock > finishBy) {
            build.conflicts.add("第 " + day + " 天约 " + formatClock(clock) + " 结束，晚于最晚结束时间 "
                    + constraints.getFinishByTime());
        }
        return build;
    }

    private Leg buildLeg(int day, PlannedNode from, PlannedNode to, Constraints constraints,
                         DirectionBudget budget, boolean avoidWalking) {
        double straight = straightLineMeters(from.location, to.location);
        String mode = chooseMode(constraints, straight, avoidWalking);
        Leg leg = new Leg();
        leg.setDay(day);
        leg.setOrigin(from.node.getPoiId());
        leg.setDestination(to.node.getPoiId());
        leg.setOriginName(from.node.getName());
        leg.setDestinationName(to.node.getName());
        leg.setMode(mode);

        AmapDirectionClient.LegResult result = null;
        if (budget.take()) {
            result = amapDirectionClient.route(modeOf(mode), from.location, to.location,
                    from.node.getCitycode());
        }
        if (result == null) {
            int distance = (int) Math.round(straight);
            leg.setMinutes(estimateMinutes(mode, distance));
            leg.setDistanceMeters(distance);
            leg.setWalkingMeters("walking".equals(mode) ? distance : null);
            leg.setCost(null);
            leg.setPolyline(new ArrayList<>());
            leg.setInstructions(new ArrayList<>());
            leg.setSource("直线估算");
            leg.setNote("高德路径规划不可用，按直线距离与经验速度估算，仅供参考");
            return leg;
        }
        leg.setMinutes(result.getMinutes() == null
                ? estimateMinutes(mode, result.getDistanceMeters() == null ? (int) straight
                : result.getDistanceMeters())
                : Math.max(0, result.getMinutes()));
        leg.setDistanceMeters(result.getDistanceMeters() == null
                ? (int) Math.round(straight) : Math.max(0, result.getDistanceMeters()));
        leg.setWalkingMeters(result.getWalkingMeters());
        leg.setCost(result.getCost());
        leg.setPolyline(result.getPolyline() == null ? new ArrayList<>() : result.getPolyline());
        leg.setInstructions(result.getInstructions() == null ? new ArrayList<>() : result.getInstructions());
        leg.setSource(blank(result.getSource()) ? "高德路径规划" : result.getSource());
        leg.setNote(blank(result.getNote()) ? "查询时参考结果，不保证未来交通情况" : result.getNote());
        return leg;
    }

    private static AmapDirectionClient.Mode modeOf(String mode) {
        if ("transit".equals(mode)) {
            return AmapDirectionClient.Mode.TRANSIT;
        }
        if ("driving".equals(mode)) {
            return AmapDirectionClient.Mode.DRIVING;
        }
        return AmapDirectionClient.Mode.WALKING;
    }

    private String chooseMode(Constraints constraints, double straightMeters, boolean avoidWalking) {
        List<String> modes = constraints.getModes() == null || constraints.getModes().isEmpty()
                ? List.of("walking", "transit") : constraints.getModes();
        boolean walking = modes.contains("walking");
        if (!avoidWalking && walking && straightMeters <= WALK_LIMIT_METERS) {
            return "walking";
        }
        if (modes.contains("transit")) {
            return "transit";
        }
        if (modes.contains("driving")) {
            return "driving";
        }
        return walking ? "walking" : modes.get(0);
    }

    private int estimateMinutes(String mode, int distanceMeters) {
        double speed = switch (mode) {
            case "transit" -> TRANSIT_METERS_PER_MINUTE;
            case "driving" -> DRIVING_METERS_PER_MINUTE;
            default -> WALK_METERS_PER_MINUTE;
        };
        return (int) Math.max(1, Math.ceil(distanceMeters / speed));
    }

    private List<String> mustVisitConflicts(Constraints constraints, List<PlannedNode> nodes) {
        List<String> conflicts = new ArrayList<>();
        for (String must : new LinkedHashSet<>(constraints.getMustVisit() == null
                ? List.<String>of() : constraints.getMustVisit())) {
            String target = must.trim();
            boolean matched = nodes.stream().anyMatch(node -> matches(target, node.node));
            if (!matched) {
                conflicts.add("未能安排必去地点「" + target + "」，请确认名称、城市或放宽条件");
            }
        }
        return conflicts;
    }

    private boolean matches(String target, RouteNode node) {
        String poiId = node.getPoiId() == null ? "" : node.getPoiId();
        String name = node.getName() == null ? "" : node.getName();
        return poiId.equalsIgnoreCase(target)
                || name.equals(target)
                || name.contains(target)
                || (!target.isEmpty() && target.contains(name) && !name.isEmpty());
    }

    private Clarification clarification(Constraints constraints, List<String> conflicts) {
        Clarification clarification = new Clarification();
        clarification.setConstraints(constraints);
        clarification.setMessage("需要补充或放宽条件后才能给出可行方案。");
        List<String> questions = new ArrayList<>(conflicts);
        if (questions.isEmpty()) {
            questions.add("请补充出行城市和可用时长。");
        }
        List<String> limited = questions.size() > MAX_QUESTIONS
                ? new ArrayList<>(questions.subList(0, MAX_QUESTIONS)) : questions;
        clarification.setQuestions(limited);
        return clarification;
    }

    /** 模型判断缺少决定性信息时的追问。 */
    public Clarification clarificationForMissingContext(PlanRequest request, String reason) {
        Constraints constraints = ConstraintDefaults.apply(request.getConstraints());
        Clarification clarification = new Clarification();
        clarification.setConstraints(constraints);
        clarification.setMessage(truncate(reason, 500));
        List<String> questions = new ArrayList<>();
        if (blank(constraints.getCity())) {
            questions.add("计划在哪个城市研学？");
        }
        questions.add("可以安排半天还是一天？");
        questions.add("有必须去的场馆或地点吗？");
        if (questions.size() > MAX_QUESTIONS) {
            questions = new ArrayList<>(questions.subList(0, MAX_QUESTIONS));
        }
        clarification.setQuestions(questions);
        return clarification;
    }

    private String buildMessage(AIRoutePlan aiPlan, List<PlannedNode> nodes, int dayCount) {
        String tag = aiPlan == null || blank(aiPlan.getTag()) ? "研学" : aiPlan.getTag();
        String names = String.join("、", nodes.stream().limit(6)
                .map(node -> node.node.getName()).toList());
        String suffix = nodes.size() > 6 ? " 等 " + nodes.size() + " 个地点" : "";
        return "按「" + tag + "」主题安排 " + nodes.size() + " 个地点，共 " + dayCount + " 天："
                + names + suffix + "。已计入通勤、缓冲与休息时间，费用为估算值。";
    }

    private List<String> assumptions(Constraints constraints, List<String> dayDates, boolean forcedNonWalking) {
        List<String> assumptions = new ArrayList<>();
        if (constraints.getDate() == null) {
            assumptions.add("未提供出行日期，开放时间按当前采集数据核对");
        }
        assumptions.add("每天从 " + constraints.getStartTime() + " 开始计时，未计入出发地到第一站的交通");
        if (!Boolean.TRUE.equals(constraints.getReturnToStart())) {
            assumptions.add("未安排返回每天出发点的交通");
        }
        assumptions.add("每个交通段额外计入 " + LEG_BUFFER_MINUTES + " 分钟缓冲");
        if (forcedNonWalking) {
            assumptions.add("为满足步行上限，已把部分步行段改为公共交通或驾车");
        }
        return assumptions;
    }

    private List<String> warnings(Constraints constraints, List<String> existing,
                                  int fallbackLegs, int missingWalkingLegs, int unknownCosts) {
        List<String> warnings = new ArrayList<>(existing);
        if (fallbackLegs > 0) {
            warnings.add("有 " + fallbackLegs + " 段交通使用直线估算，距离与时间仅供参考");
        }
        if (unknownCosts > 0) {
            warnings.add("有 " + unknownCosts + " 项费用未核实");
        }
        warnings.add("费用为估算，预约与出行日开放时间需复查");
        if (missingWalkingLegs > 0) {
            warnings.add("有 " + missingWalkingLegs + " 段交通缺少步行距离数据，未计入步行上限校验");
        }
        if (constraints.getMaxWalkingMeters() != null && missingWalkingLegs > 0) {
            warnings.add("步行上限校验数据不完整，无法确认一定满足 " + constraints.getMaxWalkingMeters() + " 米");
        }
        return warnings;
    }

    private int unknownLegCosts(List<Leg> legs) {
        int count = 0;
        for (Leg leg : legs) {
            if (leg.getCost() == null) {
                count++;
            }
        }
        return count;
    }

    private Diff diff(List<CurrentStop> currentRoute, List<PlannedNode> nodes) {
        List<String> current = new ArrayList<>();
        for (CurrentStop stop : currentRoute) {
            if (stop != null && stop.getPoiId() != null && !stop.getPoiId().isBlank()) {
                current.add(stop.getPoiId());
            }
        }
        List<String> planned = nodes.stream().map(node -> node.node.getPoiId()).toList();
        List<String> added = planned.stream()
                .filter(poiId -> current.stream().noneMatch(existing -> existing.equalsIgnoreCase(poiId)))
                .toList();
        List<String> removed = current.stream()
                .filter(poiId -> planned.stream().noneMatch(existing -> existing.equalsIgnoreCase(poiId)))
                .toList();
        return new Diff(added, removed);
    }

    private List<String> dayDates(Constraints constraints, int days) {
        List<String> dates = new ArrayList<>();
        LocalDate start = null;
        if (constraints.getDate() != null) {
            try {
                start = LocalDate.parse(constraints.getDate());
            } catch (Exception ignored) {
                start = null;
            }
        }
        for (int i = 0; i < days; i++) {
            dates.add(start == null ? null : start.plusDays(i).toString());
        }
        return dates;
    }

    private int durationOf(AIRouteItem item) {
        Integer duration = item.getRecommendedDuration();
        return duration == null ? 60 : duration;
    }

    private String learningGoal(String note) {
        if (blank(note)) {
            return "";
        }
        for (String keyword : COMMUTE_KEYWORDS) {
            if (note.contains(keyword)) {
                return "";
            }
        }
        return note;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new AgentRunCancelledException();
        }
    }

    private static Integer elapsedMs(long startedAtNanos) {
        long millis = (System.nanoTime() - startedAtNanos) / 1_000_000;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, millis));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int parseClock(String value, int fallback) {
        Integer parsed = parseClockOrNull(value);
        return parsed == null ? fallback : parsed;
    }

    private static Integer parseClockOrNull(String value) {
        if (blank(value)) {
            return null;
        }
        String[] parts = value.split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatClock(int minutes) {
        int normalized = Math.max(0, minutes);
        return String.format("%02d:%02d", (normalized / 60) % 24, normalized % 60);
    }

    private static double straightLineMeters(String origin, String destination) {
        double[] from = parseLocation(origin);
        double[] to = parseLocation(destination);
        if (from == null || to == null) {
            return 0.0;
        }
        double earthRadius = 6371000.0;
        double dLat = Math.toRadians(to[1] - from[1]);
        double dLng = Math.toRadians(to[0] - from[0]);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(from[1])) * Math.cos(Math.toRadians(to[1]))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * earthRadius * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    private static double[] parseLocation(String value) {
        if (blank(value)) {
            return null;
        }
        String[] parts = value.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
