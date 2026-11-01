package com.viyangle.study_tour.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.viyangle.study_tour.agent.dto.*;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.ReferencePair;
import com.viyangle.study_tour.pojo.VectorRetrievalResult;
import com.viyangle.study_tour.service.KnowledgeGraphRecommendService;
import com.viyangle.study_tour.service.ReferencePairService;
import com.viyangle.study_tour.service.VectorCandidateRetrieverService;
import com.viyangle.study_tour.utils.AmapDirectionClient;
import com.viyangle.study_tour.utils.AmapTransitClient;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.*;

import static com.viyangle.study_tour.agent.service.AgentDraftValidator.*;

/** 每次 run 新建一个实例。模型编辑草稿；服务端仅提供事实查询与数据合法性校验。 */
public final class RouteAgentTools {
    public record RouteStop(String poiId, Integer recommendedDuration, String notes) {}
    public record SearchResult(List<Attraction> places, List<String> retrievedTexts) {}

    private final PlanRequest request;
    private final AgentExecutionBudget budget;
    private final ObjectMapper mapper;
    private final AttractionMapper attractions;
    private final VectorCandidateRetrieverService retriever;
    private final KnowledgeGraphRecommendService graph;
    private final ReferencePairService references;
    private final AmapTransitClient placesClient;
    private final AmapDirectionClient directions;
    private final Map<String, Attraction> knownPlaces = new LinkedHashMap<>();
    private final Map<String, AmapDirectionClient.LegResult> travelCache = new HashMap<>();
    private final AgentPlan draft = new AgentPlan();
    private Clarification clarification;

    public RouteAgentTools(PlanRequest request, AgentExecutionBudget budget, ObjectMapper mapper,
                           AttractionMapper attractions, VectorCandidateRetrieverService retriever,
                           KnowledgeGraphRecommendService graph, ReferencePairService references,
                           AmapTransitClient placesClient, AmapDirectionClient directions) {
        this.request = request;
        this.budget = budget;
        this.mapper = mapper;
        this.attractions = attractions;
        this.retriever = retriever;
        this.graph = graph;
        this.references = references;
        this.placesClient = placesClient;
        this.directions = directions;
        // 保留缺失字段，避免默认值掩盖模型应向用户确认的信息。
        draft.setConstraints(request.getConstraints() == null ? new Constraints()
                : mapper.convertValue(request.getConstraints(), Constraints.class));
        draft.setFeasible(null); // 可行性由模型判断；服务端不会将合法性校验等同于可行。
        for (CurrentStop stop : request.currentRouteOrEmpty()) {
            RouteNode node = new RouteNode();
            node.setPoiId(normalizeId(stop.getPoiId()));
            node.setName(stop.getName());
            node.setRecommendedDuration(stop.getRecommendedDuration());
            node.setNotes(stop.getNotes());
            node.setVisitOrder(draft.getNodes().size() + 1);
            draft.getNodes().add(node);
        }
    }

    @Tool(name = "search_places", value = "按自然语言需求检索景点知识库（RAG）。可按城市过滤。返回真实 POI 和检索片段；不生成路线。")
    public SearchResult searchPlaces(@P("语义检索文本") String query,
                                     @P(value = "城市名或行政区编码，可省略", required = false) String city,
                                     @P(value = "数量 1–30，默认 10", required = false) Integer limit) {
        require(query != null && !query.isBlank() && query.length() <= 2000, "query 必须为 1–2000 字符");
        int size = limit(limit);
        VectorRetrievalResult result = retriever.retrieveCandidatesWithTexts(query, 60, 5);
        budget.checkActive();
        List<String> ids = result == null || result.getPoiIds() == null ? List.of() : result.getPoiIds();
        List<Attraction> found = ids.isEmpty() ? List.of() : attractions.selectActiveByPoiIds(ids);
        List<Attraction> selected = select(found, city, size);
        return new SearchResult(selected,
                result == null || result.getRetrievedTexts() == null ? List.of() : result.getRetrievedTexts());
    }

    @Tool(name = "search_graph_places", value = "按研学标签和地区从知识图谱查询候选景点。RAG 无结果时也可使用；不生成路线。")
    public List<Attraction> searchGraphPlaces(@P("研学标签列表，可为空数组") List<String> tags,
                                              @P(value = "行政区编码，可省略", required = false) String regionCode,
                                              @P(value = "城市名，可省略", required = false) String city,
                                              @P(value = "数量 1–30，默认 10", required = false) Integer limit) {
        require(tags != null && tags.size() <= 15 && tags.stream().allMatch(t -> t != null && t.length() <= 80), "无效标签列表");
        require(regionCode == null || regionCode.matches("\\d{2,6}"), "无效行政区编码");
        List<Attraction> found = graph.retrieveCandidatesByGraph(tags, regionCode, limit(limit));
        budget.checkActive();
        return select(found, city, limit(limit));
    }

    @Tool(name = "get_place_details", value = "核实指定 POI 的真实名称、坐标、开放时间等信息。先查数据库，不存在时查询高德。")
    public List<Attraction> getPlaceDetails(@P("POI ID 列表，最多 30 个") List<String> poiIds) {
        require(poiIds != null && !poiIds.isEmpty() && poiIds.size() <= 30, "poiIds 必须包含 1–30 项");
        List<Attraction> result = new ArrayList<>();
        for (String id : poiIds) result.add(place(normalizeId(id)));
        return result;
    }

    @Tool(name = "search_reference_pairs", value = "按研学标签查询参考景点组合，不代表必须采用该组合。")
    public List<ReferencePair> searchReferencePairs(@P(value = "研学标签，省略时不过滤", required = false) String tag,
                                                   @P(value = "页码，从 1 开始", required = false) Integer pageNum,
                                                   @P(value = "每页 1–30 项", required = false) Integer pageSize) {
        require(pageNum == null || pageNum >= 1 && pageNum <= 100, "无效页码");
        return references.recommendReferencePairs(tag, pageNum == null ? 1 : pageNum, limit(pageSize));
    }

    @Tool(name = "calculate_leg", value = "查询一个有方向的交通段，方式由你选择。返回地图事实，不会修改路线。定位出发点可用 __origin__ 表示。")
    public AmapDirectionClient.LegResult calculateLeg(@P("起点 POI ID 或 __origin__") String fromPoiId,
                                                       @P("终点 POI ID 或 __origin__") String toPoiId,
                                                       @P("walking、transit 或 driving") String mode) {
        require(Set.of("walking", "transit", "driving").contains(text(mode)), "无效交通方式");
        String from = normalizeEndpoint(fromPoiId);
        String to = normalizeEndpoint(toPoiId);
        require(!from.equals(to), "起终点不能相同");
        Attraction a = endpoint(from);
        Attraction b = endpoint(to);
        String key = from + "|" + to + "|" + mode;
        if (!travelCache.containsKey(key)) {
            require(budget.takeDirectionRequest(), "已达到本轮地图查询上限");
            AmapDirectionClient.LegResult result = directions.route(AmapDirectionClient.Mode.valueOf(mode.toUpperCase(Locale.ROOT)),
                    a.getLocation(), b.getLocation(), a.getCitycode() == null ? b.getCitycode() : a.getCitycode());
            budget.checkActive();
            if (result != null) travelCache.put(key, result);
            require(result != null, "地图未返回该交通段，请更换方式或说明数据未知");
        }
        return travelCache.get(key);
    }

    @Tool(name = "get_route", value = "读取本轮路线草稿、交通、日程及约束；不会自动检索或修改任何内容。")
    public AgentPlan getRoute() { return draft; }

    @Tool(name = "ask_user", value = "终止本轮循环并向用户追问。发现当前任务所需的 tag/兴趣、预算、城市、时长等信息缺失或含糊时调用。成功后立即返回追问，不再调用模型或其他工具；不要同时请求其他工具。")
    public Clarification askUser(@P("简短说明需要确认什么") String message,
                                 @P("1–3 个具体问题，合并询问当前缺失的必要信息") List<String> questions) {
        require(message != null && !message.isBlank() && message.length() <= 8000, "message 必须为 1–8000 字符");
        require(questions != null && !questions.isEmpty() && questions.size() <= 3
                && questions.stream().allMatch(q -> q != null && !q.isBlank() && q.length() <= 2000), "questions 必须包含 1–3 个非空问题");
        Clarification result = new Clarification();
        result.setMessage(message);
        result.setQuestions(List.copyOf(questions));
        result.setConstraints(mapper.convertValue(draft.getConstraints(), Constraints.class));
        clarification = result;
        return result;
    }

    Clarification clarification() { return clarification; }

    @Tool(name = "update_constraints", value = "把用户本轮语义要求更新为结构化约束。只合并非 null 字段，空列表表示清空列表。服务端仅校验参数范围，不代替你判断约束冲突。")
    public Constraints updateConstraints(@P("需要更新的约束字段") Constraints constraints) {
        require(constraints != null && constraints.getExtraFields().isEmpty(), "无效约束字段");
        ObjectNode merged = mapper.valueToTree(draft.getConstraints());
        ObjectNode patch = mapper.valueToTree(constraints);
        patch.fields().forEachRemaining(field -> { if (!field.getValue().isNull()) merged.set(field.getKey(), field.getValue()); });
        Constraints updated = mapper.convertValue(merged, Constraints.class);
        PlanRequest candidate = mapper.convertValue(request, PlanRequest.class);
        candidate.setConstraints(updated);
        List<Map<String, Object>> errors = new PlanRequestValidator().validate(candidate);
        require(errors.isEmpty(), "约束字段不合法：" + errors);
        draft.setConstraints(updated);
        return updated;
    }

    @Tool(name = "clear_constraints", value = "按用户明确要求清除旧约束，例如用户表示预算不限时清除 budget；也可清除旧兴趣以重新确认 tag。不会自行决定应该清除哪些字段。")
    public Constraints clearConstraints(@P("需要清除的字段名列表，例如 [budget]，最多 18 项") List<String> fields) {
        require(fields != null && !fields.isEmpty() && fields.size() <= 18, "请提供要清除的约束字段");
        ObjectNode updated = mapper.valueToTree(draft.getConstraints());
        for (String field : fields) {
            require(field != null && updated.has(field), "未知约束字段：" + field);
        }
        fields.forEach(updated::putNull);
        Constraints result = mapper.convertValue(updated, Constraints.class);
        draft.setConstraints(result);
        return result;
    }

    @Tool(name = "edit_route", value = "用完整有序站点列表替换当前草稿，实现新增、删除、替换、重排。不会写入正式路线。顺序和停留时长由你决定。修改后清空旧交通和日程，需重新编辑。")
    public AgentPlan editRoute(@P("完整有序站点列表，1–20 个；每项含 poiId、recommendedDuration（分钟）、notes") List<RouteStop> stops) {
        require(stops != null && !stops.isEmpty() && stops.size() <= 20, "路线必须包含 1–20 个站点");
        List<RouteNode> nodes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (RouteStop stop : stops) {
            require(stop != null, "站点不能为 null");
            String id = normalizeId(stop.poiId());
            require(ids.add(id), "路线不能包含重复 POI");
            require(stop.recommendedDuration() != null && stop.recommendedDuration() >= 1 && stop.recommendedDuration() <= 1440,
                    "recommendedDuration 必须在 1–1440 分钟之间");
            require(stop.notes() == null || stop.notes().length() <= 2000, "站点说明过长");
            RouteNode node = routeNode(place(id));
            node.setPoiId(id);
            node.setVisitOrder(nodes.size() + 1);
            node.setRecommendedDuration(stop.recommendedDuration());
            node.setNotes(stop.notes());
            nodes.add(node);
        }
        draft.setNodes(nodes);
        draft.setLegs(new ArrayList<>());
        draft.setDays(new ArrayList<>());
        return draft;
    }

    @Tool(name = "edit_transport", value = "用完整交通段列表替换草稿交通。由你填写 day、起终点 POI、mode、时长、距离、费用、指引和来源。未知数字用 null；服务端不选择交通、不估算时间或费用。")
    public List<Leg> editTransport(@P("完整交通段列表；无交通可传空数组") List<Leg> legs) {
        validateLegs(legs, routeIds(), request.getOrigin() != null);
        draft.setLegs(new ArrayList<>(legs));
        return draft.getLegs();
    }

    @Tool(name = "edit_schedule", value = "用完整逐日日程替换草稿时间安排。由你安排日期、到达离开、停留、休息、研学目标及每天汇总。服务端只检查时间格式、顺序、数值及引用，不自动排程，不判断预算、步行、开放时间是否满足。")
    public List<DaySummary> editSchedule(@P("完整每日安排；每天含 day、date、schedule、totalMinutes、walkingMeters、estimatedCost、legCount、finishTime") List<DaySummary> days) {
        validateDays(days, routeIds());
        draft.setDays(new ArrayList<>(days));
        draft.getNodes().forEach(node -> node.setVisitTime(null));
        for (DaySummary day : days) {
            for (ScheduleItem item : day.getSchedule()) {
                if (item.getPoiId() != null) {
                    draft.getNodes().stream().filter(node -> node.getPoiId().equals(item.getPoiId())).findFirst().ifPresent(node ->
                            node.setVisitTime((day.getDate() == null || day.getDate().isBlank() ? "第" + day.getDay() + "天" : day.getDate())
                                    + " " + item.getArrival()));
                }
            }
        }
        return draft.getDays();
    }

    @Tool(name = "validate_itinerary", value = "只校验草稿的数据合法性与引用完整性。不计算、重排或判断行程业务可行性。业务判断和最终 feasible 标志由你给出。")
    public Map<String, Object> validateItinerary() {
        validateComplete(draft, request.getOrigin() != null);
        return Map.of("valid", true, "scope", "仅数据合法性；未判定业务可行性", "draft", draft);
    }

    AgentPlan completePlan(String message, boolean feasible, List<String> issues,
                           List<String> warnings, List<String> assumptions, Double cost, Integer unknownCosts) {
        validateComplete(draft, request.getOrigin() != null);
        require(knownPlaces.keySet().containsAll(routeIds()), "路线中有未经工具核实的 POI，请先查询详情或编辑路线");
        money(cost, "estimatedCost");
        nonNegative(unknownCosts, "unknownCosts");
        AgentPlan result = mapper.convertValue(draft, AgentPlan.class);
        List<RouteNode> verifiedNodes = new ArrayList<>();
        for (RouteNode selected : result.getNodes()) {
            RouteNode node = routeNode(knownPlaces.get(selected.getPoiId()));
            node.setPoiId(selected.getPoiId());
            node.setVisitOrder(selected.getVisitOrder());
            node.setVisitTime(selected.getVisitTime());
            node.setRecommendedDuration(selected.getRecommendedDuration());
            node.setNotes(selected.getNotes() == null ? "" : selected.getNotes());
            verifiedNodes.add(node);
        }
        result.setNodes(verifiedNodes);
        result.setMessage(message);
        result.setFeasible(feasible);
        result.setIssues(issues);
        result.setWarnings(warnings);
        result.setAssumptions(assumptions);
        result.setEstimatedCost(cost);
        result.setUnknownCosts(unknownCosts);
        Set<String> oldIds = new LinkedHashSet<>();
        request.currentRouteOrEmpty().forEach(stop -> oldIds.add(normalizeId(stop.getPoiId())));
        Set<String> newIds = routeIds();
        result.setAddedPoiIds(newIds.stream().filter(id -> !oldIds.contains(id)).toList());
        result.setRemovedPoiIds(oldIds.stream().filter(id -> !newIds.contains(id)).toList());
        return result;
    }

    private List<Attraction> select(List<Attraction> found, String city, int limit) {
        List<Attraction> selected = new ArrayList<>();
        if (found == null) return selected;
        for (Attraction place : found) {
            budget.checkActive();
            if (place == null || place.getPoiId() == null || !active(place)) continue;
            if (city != null && !city.isBlank() && !(text(place.getCityname()).contains(city)
                    || text(place.getAdcode()).startsWith(city) || city.equals(place.getCitycode()))) continue;
            knownPlaces.put(normalizeId(place.getPoiId()), place);
            selected.add(place);
            if (selected.size() == limit) break;
        }
        return selected;
    }

    private Attraction place(String id) {
        budget.checkActive();
        Attraction result = knownPlaces.get(id);
        if (result == null) result = attractions.selectByPoiId(id);
        if (result == null) result = placesClient.fetchAttraction(id);
        budget.checkActive();
        require(result != null && result.getPoiId() != null && id.equalsIgnoreCase(result.getPoiId()), "无法核实 POI：" + id);
        require(active(result), "POI 已失效：" + id);
        knownPlaces.put(id, result);
        return result;
    }

    private Attraction endpoint(String id) {
        if (!"__origin__".equals(id)) return place(id);
        Origin origin = request.getOrigin();
        require(origin != null, "请求未提供定位出发点");
        coordinate(origin.getLongitude(), origin.getLatitude());
        Attraction result = new Attraction();
        result.setPoiId("__origin__");
        result.setLocation(origin.getLongitude() + "," + origin.getLatitude());
        return result;
    }

    private Set<String> routeIds() {
        Set<String> ids = new LinkedHashSet<>();
        draft.getNodes().forEach(node -> ids.add(node.getPoiId()));
        return ids;
    }

    private static boolean active(Attraction place) {
        return place.getStatus() == null || "ACTIVE".equalsIgnoreCase(place.getStatus());
    }

    private static RouteNode routeNode(Attraction place) {
        RouteNode node = new RouteNode();
        node.setName(text(place.getName()));
        node.setLocation(text(place.getLocation()));
        node.setAddress(text(place.getAddress()));
        node.setCityname(text(place.getCityname()));
        node.setCitycode(text(place.getCitycode()));
        node.setAdcode(text(place.getAdcode()));
        node.setType(text(place.getType()));
        node.setTypecode(text(place.getTypecode()));
        node.setTel(text(place.getTel()));
        node.setOpentimeToday(text(place.getOpentimeToday()));
        node.setOpentimeWeek(text(place.getOpentimeWeek()));
        return node;
    }

    private static int limit(Integer limit) {
        require(limit == null || limit >= 1 && limit <= 30, "limit 必须在 1–30 之间");
        return limit == null ? 10 : limit;
    }
    private static String normalizeEndpoint(String id) { return "__origin__".equals(id) ? id : normalizeId(id); }
    private static String normalizeId(String id) {
        require(id != null && id.matches("[A-Za-z0-9_-]{1,80}"), "无效 POI ID");
        return id.toUpperCase(Locale.ROOT);
    }
}
