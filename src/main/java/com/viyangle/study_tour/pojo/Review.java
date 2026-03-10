package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Review {
    private Long id;
    private Long projectId;
    private Long routeId;
    private Long fromAccountId;
    private Long toAccountId;
    private String reviewType;
    private Integer overallScore;
    private String content;
    private LocalDateTime createdAt;
}
