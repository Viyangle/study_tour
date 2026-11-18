package com.viyangle.study_tour.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.annotation.OperationLog;
import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.FeedbackEventRequest;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.pojo.TagDimension;
import com.viyangle.study_tour.pojo.TagFeedbackEvent;
import com.viyangle.study_tour.service.TagFeedbackEventType;
import com.viyangle.study_tour.service.TagFeedbackService;
import com.viyangle.study_tour.service.TagVectorService;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 前端反馈入口。服务端只能拿到登录身份，不信任请求里的 accountId。
 */
@Slf4j
@RestController
@RequestMapping("/feedback")
public class FeedbackController {

    private static final int MAX_EVENTS = 50;

    /**
     * 客户端显式埋点只允许这些类型。
     *
     * <p>路线保留/淘汰/发布、加入项目、评价等属于服务端派生证据，
     * 只能由后端在业务事务里产生，不能接受客户端直接上报，否则任何登录用户
     * 都能给任意景点刷属性权重。</p>
     */
    private static final Set<TagFeedbackEventType> CLIENT_EVENT_TYPES = EnumSet.of(
            TagFeedbackEventType.IMPRESSION,
            TagFeedbackEventType.ATTRACTION_DETAIL_VIEW,
            TagFeedbackEventType.THUMBS_UP,
            TagFeedbackEventType.THUMBS_DOWN,
            TagFeedbackEventType.TAG_CORRECTION);

    @Autowired
    private TagFeedbackService tagFeedbackService;

    @Autowired
    private TagVectorService tagVectorService;

    @Autowired(required = false)
    private AttractionMapper attractionMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @PostMapping("/events")
    @OperationLog(value = "提交标签反馈", type = "TAG_FEEDBACK")
    public Result ingest(@RequestBody FeedbackEventRequest request) {
        if (request == null || request.getEvents() == null || request.getEvents().isEmpty()) {
            throw new IllegalArgumentException("events cannot be empty");
        }
        if (request.getEvents().size() > MAX_EVENTS) {
            throw new IllegalArgumentException("events cannot exceed " + MAX_EVENTS);
        }

        Long accountId = SecurityContextUtil.currentAccountId();
        int currentDimVersion = tagVectorService == null ? 0 : tagVectorService.dimVersion();
        if (request.getDimVersion() != null && currentDimVersion > 0
                && !request.getDimVersion().equals(currentDimVersion)) {
            // 维度版本不一致时不能按当前下标解释旧 intent_vector，整批忽略比错误落库安全。
            Map<String, Integer> data = new LinkedHashMap<>();
            data.put("accepted", 0);
            data.put("duplicates", 0);
            data.put("ignored", request.getEvents().size());
            log.warn("标签反馈维度版本不匹配: requestVersion={}, currentVersion={}, accountId={}",
                    request.getDimVersion(), currentDimVersion, accountId);
            return Result.success(data);
        }

        Map<String, Long> tagIdByCode = tagIdByCode();
        int accepted = 0;
        int duplicates = 0;
        int ignored = 0;

        for (FeedbackEventRequest.Item item : request.getEvents()) {
            TagFeedbackEventType type = item == null ? null : TagFeedbackEventType.from(item.getType());
            if (type == null || !CLIENT_EVENT_TYPES.contains(type)) {
                ignored++;
                continue;
            }

            String poiId = blankToNull(item.getPoiId());
            if (poiId == null) {
                ignored++;
                continue;
            }
            if (attractionMapper != null && !attractionExists(poiId)) {
                ignored++;
                continue;
            }

            Long tagId = resolveTagId(item, tagIdByCode);
            if (type == TagFeedbackEventType.TAG_CORRECTION && tagId == null) {
                ignored++;
                continue;
            }

            TagFeedbackEvent event = new TagFeedbackEvent();
            event.setAccountId(accountId);
            event.setSessionKey(normalizeSessionKey(request.getSessionKey()));
            event.setSource("CLIENT");
            event.setEventType(type.name());
            event.setPoiId(poiId);
            // 客户端不能指定路线/项目目标；服务端派生事件走内部 recordRouteAction/recordProjectAction。
            event.setRouteId(null);
            event.setProjectId(null);
            event.setTagId(tagId);
            event.setEventValue(resolveValue(type, item));
            event.setOccurredAt(resolveTime(item.getOccurredAt()));
            event.setDimVersion(request.getDimVersion() != null ? request.getDimVersion() : currentDimVersion);
            event.setScene(item.getScene());
            event.setPosition(item.getPosition());
            event.setIdempotencyKey(resolveKey(accountId, item.getIdempotencyKey()));
            event.setContext(resolveContext(item));

            int inserted = tagFeedbackService.record(event);
            if (inserted > 0) {
                accepted++;
            } else {
                duplicates++;
            }
        }

        log.info("标签反馈接收完成: accountId={}, accepted={}, duplicates={}, ignored={}",
                accountId, accepted, duplicates, ignored);
        Map<String, Integer> data = new LinkedHashMap<>();
        data.put("accepted", accepted);
        data.put("duplicates", duplicates);
        data.put("ignored", ignored);
        return Result.success(data);
    }

    private Map<String, Long> tagIdByCode() {
        Map<String, Long> result = new LinkedHashMap<>();
        List<TagDimension> dimensions = tagVectorService.dimensions();
        for (TagDimension dimension : dimensions) {
            if (dimension.getCode() != null && dimension.getTagId() != null) {
                result.put(dimension.getCode(), dimension.getTagId());
            }
        }
        return result;
    }

    private Long resolveTagId(FeedbackEventRequest.Item item, Map<String, Long> tagIdByCode) {
        if (item.getTagCode() == null || item.getTagCode().isBlank()) {
            return null;
        }
        return tagIdByCode.get(item.getTagCode().trim().toUpperCase(Locale.ROOT));
    }

    private double resolveValue(TagFeedbackEventType type, FeedbackEventRequest.Item item) {
        return switch (type) {
            case TAG_CORRECTION -> {
                double sign = item.getValue() == null ? 1.0 : Math.signum(item.getValue());
                if (sign == 0.0) {
                    sign = 1.0;
                }
                yield sign * Math.abs(type.defaultWeight());
            }
            // 曝光/详情/点赞点踩只接受事件语义，不接受客户端自定义强度。
            default -> type.defaultWeight();
        };
    }

    private LocalDateTime resolveTime(LocalDateTime occurredAt) {
        LocalDateTime now = LocalDateTime.now();
        if (occurredAt == null || occurredAt.isAfter(now)) {
            return now;
        }
        // 保留真实发生时间，过期与否交给 TagFeedbackProcessor 的 max-event-age-days 判断。
        return occurredAt;
    }

    private boolean attractionExists(String poiId) {
        try {
            Attraction attraction = attractionMapper.selectByPoiId(poiId);
            return attraction != null;
        } catch (Exception e) {
            // 查询失败时放行，由反馈处理器按无目标景点忽略，不能因为校验查询失败阻断采集。
            log.warn("反馈景点存在性校验失败: poiId={}, {}", poiId, e.getMessage());
            return true;
        }
    }

    private String normalizeSessionKey(String sessionKey) {
        if (sessionKey == null) {
            return null;
        }
        String trimmed = sessionKey.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
    }

    private String resolveKey(Long accountId, String clientKey) {
        String suffix = clientKey == null || clientKey.isBlank()
                ? UUID.randomUUID().toString()
                : clientKey.trim();
        // idempotency_key 列宽 80，客户端 key 过长时用短哈希，避免插入失败被误记为 duplicate。
        if (suffix.length() > 64) {
            suffix = Integer.toHexString(suffix.hashCode());
        }
        return "api:" + (accountId == null ? "anonymous" : accountId) + ":" + suffix;
    }

    private String resolveContext(FeedbackEventRequest.Item item) {
        if ((item.getScene() == null || item.getScene().isBlank()) && item.getPosition() == null) {
            return null;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        if (item.getScene() != null && !item.getScene().isBlank()) {
            context.put("scene", item.getScene());
        }
        if (item.getPosition() != null) {
            context.put("position", item.getPosition());
        }
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
