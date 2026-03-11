package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteAttraction {
    private Long id;
    private Long routeId;
    private Long attractionId;
    private Integer visitOrder;
    private LocalTime visitTime;
    private Integer recommendedDuration;
    private String notes;
    private LocalDateTime createdAt;
}
