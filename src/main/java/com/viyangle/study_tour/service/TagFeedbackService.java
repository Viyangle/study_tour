package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.TagFeedbackEvent;

/**
 * 反馈事件登记。只负责写入 append-only 事件表，实际权重更新由定时处理器完成。
 */
public interface TagFeedbackService {

    boolean isEnabled();

    /** 幂等写入，重复键返回 0。 */
    int record(TagFeedbackEvent event);

    int recordRouteAction(String type, Long routeId, Long accountId);

    int recordProjectAction(String type, Long projectId, Long accountId);

    int recordReview(Long reviewId, Long projectId, Long routeId, Integer score, Long accountId);
}
