package com.viyangle.study_tour.pojo;

import lombok.Data;

import java.util.List;

@Data
public class ReferenceRouteUpsertRequest {
    private String tag;
    private List<ReferenceRouteAttraction> routeAttractions;
}
