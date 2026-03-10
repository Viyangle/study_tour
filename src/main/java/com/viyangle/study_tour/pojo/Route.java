package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Route {
    private Long id;
    private Long creatorAccountId;
    private String title;
    private String regionCode;
    private Integer days;
    private String outline;
    private String detailJson;
    private LocalDateTime createdAt;
}
