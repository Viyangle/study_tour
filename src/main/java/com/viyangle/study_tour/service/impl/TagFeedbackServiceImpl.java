package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.TagFeedbackEventMapper;
import com.viyangle.study_tour.pojo.TagFeedbackEvent;
import com.viyangle.study_tour.service.TagFeedbackEventType;
import com.viyangle.study_tour.service.TagFeedbackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 事件登记实现：业务动作只插一行 PENDING，不直接改向量，避免反馈故障影响主流程。
 */
@Slf4j
@Service
public class TagFeedbackServiceImpl implements TagFeedbackService {

    @Autowired
    private TagFeedbackEventMapper tagFeedbackEventMapper;

    @Value("${app.tag-vector.feedback-enabled:true}")
    private boolean enabled = true;

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public int record(TagFeedbackEvent event) {
        if (!enabled || event == null || event.getEventType() == null || event.getEventType().isBlank()) {
            return 0;
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(LocalDateTime.now());
        }
        event.setStatus("PENDING");
        event.setAttempts(0);
        int inserted = tagFeedbackEventMapper.insertIgnore(event);
        if (inserted > 0) {
            log.debug("反馈事件已登记: type={}, poiId={}, routeId={}, projectId={}",
                    event.getEventType(), event.getPoiId(), event.getRouteId(), event.getProjectId());
        }
        return inserted;
    }

    @Override
    public int recordRouteAction(String type, Long routeId, Long accountId) {
        TagFeedbackEventType eventType = TagFeedbackEventType.from(type);
        if (eventType == null || routeId == null) {
            return 0;
        }
        TagFeedbackEvent event = baseEvent(eventType, accountId);
        event.setRouteId(routeId);
        event.setEventValue(eventType.defaultWeight());
        event.setIdempotencyKey(key(eventType, "route", routeId, accountId, null));
        return record(event);
    }

    @Override
    public int recordProjectAction(String type, Long projectId, Long accountId) {
        TagFeedbackEventType eventType = TagFeedbackEventType.from(type);
        if (eventType == null || projectId == null) {
            return 0;
        }
        TagFeedbackEvent event = baseEvent(eventType, accountId);
        event.setProjectId(projectId);
        event.setEventValue(eventType.defaultWeight());
        event.setIdempotencyKey(key(eventType, "project", projectId, accountId, null));
        return record(event);
    }

    @Override
    public int recordReview(Long reviewId, Long projectId, Long routeId, Integer score, Long accountId) {
        if (score == null || projectId == null) {
            return 0;
        }
        TagFeedbackEvent event = baseEvent(TagFeedbackEventType.REVIEW_SCORE, accountId);
        event.setProjectId(projectId);
        event.setRouteId(routeId);
        // 5 分 → +0.4，1 分 → -0.4，3 分 → 0
        event.setEventValue((score - 3) / 2.0 * 0.4);
        event.setIdempotencyKey(key(TagFeedbackEventType.REVIEW_SCORE, "review", reviewId, accountId, null));
        return record(event);
    }

    private TagFeedbackEvent baseEvent(TagFeedbackEventType type, Long accountId) {
        TagFeedbackEvent event = new TagFeedbackEvent();
        event.setEventType(type.name());
        event.setAccountId(accountId);
        event.setOccurredAt(LocalDateTime.now());
        return event;
    }

    /**
     * 服务端动作的去重键：同一动作、同一对象、同一账号、同一天只计一次，
     * 既挡住双击/重试，又允许用户第二天重新收藏/加入。
     */
    private String key(TagFeedbackEventType type, String target, Long targetId, Long accountId, Long extraId) {
        String suffix = extraId != null ? String.valueOf(extraId) : LocalDate.now().toString();
        return type.name() + ":" + target + ":" + (targetId == null ? "-" : targetId)
                + ":" + (accountId == null ? "anonymous" : accountId) + ":" + suffix;
    }
}
