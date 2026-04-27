package com.viyangle.study_tour.pojo;

import lombok.Data;

@Data
public class ReferenceRouteRecommendRequest {
    private Long accountId;
    private Integer pageNum;
    private Integer pageSize;
}
