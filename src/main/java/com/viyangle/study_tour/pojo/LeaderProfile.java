package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaderProfile {
    private Long accountId;
    private String intro;
    private Integer totalRating;
    private Integer ratingCount;
}
