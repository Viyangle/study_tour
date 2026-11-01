package com.viyangle.study_tour.pojo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * POST /feedback/events 的批量请求。
 */
@Data
public class FeedbackEventRequest {
    private String sessionKey;
    private Integer dimVersion;
    private List<Item> events;

    @Data
    public static class Item {
        private String type;
        private String poiId;
        private Long routeId;
        private Long projectId;
        private String tagCode;
        private Double value;
        private String scene;
        private Integer position;
        private String idempotencyKey;
        private LocalDateTime occurredAt;
    }
}
