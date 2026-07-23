package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Aggregated read model for the leader "My profile" screen.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaderProfileView {
    private Long accountId;
    private String username;
    private String avatarUrl;
    private String regionCode;
    private String intro;
    private Double averageRating;
    private Integer ratingCount;
    private Integer acceptedOrderCount;
    private Integer completedOrderCount;
    private List<String> tagNames;
    private List<LeaderReviewView> recentReviews;
}
