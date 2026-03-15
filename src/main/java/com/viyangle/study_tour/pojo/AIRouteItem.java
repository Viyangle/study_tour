package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AIRouteItem {
    private Long attractionId;
    private Integer visitOrder;
    private String visitTime;
    private Integer recommendedDuration;
    private String notes;
}
