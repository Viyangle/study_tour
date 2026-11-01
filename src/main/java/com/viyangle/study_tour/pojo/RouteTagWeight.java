package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 路线-标签向量行（route_tag_weight）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteTagWeight {
    private Long routeId;
    private Long tagId;
    private Double weight;
    private String source;
    private Integer vectorVersion;
    private LocalDateTime updatedAt;
}
