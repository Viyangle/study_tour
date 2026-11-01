package com.viyangle.study_tour.service;

import java.util.Locale;

/**
 * 反馈事件类型与默认权重（正数=正向证据，负数=负向证据，0=只做曝光/意图记录）。
 */
public enum TagFeedbackEventType {

    IMPRESSION(0.0),
    ATTRACTION_DETAIL_VIEW(0.10),
    ROUTE_STOP_ADD(0.60),
    ROUTE_STOP_KEEP(0.30),
    ROUTE_STOP_REMOVE(-0.60),
    /** 最终发布路线上的景点：唯一的大幅正向证据。 */
    ROUTE_STOP_CONFIRM(0.80),
    /** 过程中出现过、但最终没进路线：轻降，幅度小于 CONFIRM。 */
    ROUTE_STOP_REJECT(-0.20),
    /** 候选列表展示过、但从未被选进任何路线：曝光级轻降。 */
    ROUTE_STOP_NOT_SELECTED(-0.02),
    ROUTE_FAVORITE(0.50),
    ROUTE_UNFAVORITE(-0.30),
    ROUTE_SAVED(0.40),
    ROUTE_PUBLISH(1.00),
    PROJECT_JOIN(1.00),
    PROJECT_QUIT(-0.70),
    PROJECT_PUBLISH(1.00),
    REVIEW_SCORE(0.0),
    THUMBS_UP(0.80),
    THUMBS_DOWN(-0.80),
    TAG_CORRECTION(2.00),
    TAG_FILTER(0.0),
    AGENT_INTEREST(0.0),
    SEARCH_QUERY(0.0);

    private final double defaultWeight;

    TagFeedbackEventType(double defaultWeight) {
        this.defaultWeight = defaultWeight;
    }

    public double defaultWeight() {
        return defaultWeight;
    }

    /**
     * 是否允许更新「景点-标签」属性权重。
     *
     * <p>只有路线反复生成过程中的保留/新增/删除、路线保存/发布和显式纠正属于属性证据；
     * 点赞点踩、收藏、加入项目、评价都只是用户偏好，不再改景点 tag。</p>
     */
    public boolean affectsAttractionTags() {
        return switch (this) {
            case TAG_CORRECTION, ROUTE_STOP_ADD, ROUTE_STOP_KEEP, ROUTE_STOP_REMOVE,
                 ROUTE_STOP_CONFIRM, ROUTE_STOP_REJECT, ROUTE_STOP_NOT_SELECTED,
                 ROUTE_SAVED, ROUTE_PUBLISH -> true;
            default -> false;
        };
    }

    public static TagFeedbackEventType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (TagFeedbackEventType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
