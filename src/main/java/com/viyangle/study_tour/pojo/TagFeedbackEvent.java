package com.viyangle.study_tour.pojo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 标签反馈事件（对应 tag_feedback_event，append-only）。
 */
@Data
@NoArgsConstructor
public class TagFeedbackEvent {
    private Long id;
    private Long accountId;
    private String sessionKey;
    private String eventType;
    private String poiId;
    private Long routeId;
    private Long projectId;
    private Long tagId;
    private Double eventValue;
    private String intentVector;
    private String context;
    private LocalDateTime occurredAt;
    private LocalDateTime receivedAt;
    private String idempotencyKey;
    private String status;
    private Integer attempts;
    private LocalDateTime appliedAt;
    private String applyNote;
}
