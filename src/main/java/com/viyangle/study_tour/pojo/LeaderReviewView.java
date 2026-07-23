package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Review data required by the leader profile and review list screens.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaderReviewView {
    private Long id;
    private Long projectId;
    private Long routeId;
    private Long reviewerAccountId;
    private String reviewerName;
    private String reviewerAvatarUrl;
    private Integer overallScore;
    private String content;
    private LocalDateTime createdAt;
}
