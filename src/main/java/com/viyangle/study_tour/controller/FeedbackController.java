package com.viyangle.study_tour.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.annotation.OperationLog;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 前端反馈入口。服务端只能拿到登录身份，不信任请求里的 accountId。
 */
@Slf4j
@RestController
@RequestMapping("/feedback")
public class FeedbackController {

    private static final int MAX_EVENTS = 50;

    @Autowired
    private TagFeedbackService tagFeedbackService;

    @Autowired
    private TagVectorService tagVectorService;

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
        Map<String, Long> tagIdByCode = tagIdByCode();
        int accepted = 0;
        int duplicates = 0;
        int ignored = 0;

        for (FeedbackEventRequest.Item item : request.getEvents()) {
            TagFeedbackEventType type = item == null ? null : TagFeedbackEventType.from(item.getType());
            if (type == null) {
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
            event.setSessionKey(request.getSessionKey());
            event.setEventType(type.name());
            event.setPoiId(blankToNull(item.getPoiId()));
            event.setRouteId(item.getRouteId());
            event.setProjectId(item.getProjectId());
            event.setTagId(tagId);
            event.setEventValue(resolveValue(type, item));
            event.setOccurredAt(resolveTime(item.getOccurredAt()));
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
        if (type == TagFeedbackEventType.TAG_CORRECTION) {
            double sign = item.getValue() == null ? 1.0 : Math.signum(item.getValue());
            if (sign == 0.0) {
                sign = 1.0;
            }
            return sign * Math.abs(type.defaultWeight());
        }
        if (item.getValue() != null) {
            return Math.max(-5.0, Math.min(5.0, item.getValue()));
        }
        return type.defaultWeight();
    }

    private LocalDateTime resolveTime(LocalDateTime occurredAt) {
        LocalDateTime now = LocalDateTime.now();
        if (occurredAt == null) {
            return now;
        }
        LocalDateTime earliest = now.minusMinutes(10);
        if (occurredAt.isBefore(earliest)) {
            return earliest;
        }
        return occurredAt.isAfter(now) ? now : occurredAt;
    }

    private String resolveKey(Long accountId, String clientKey) {
        String suffix = clientKey == null || clientKey.isBlank()
                ? UUID.randomUUID().toString()
                : clientKey.trim();
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
