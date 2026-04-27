package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceRouteDetail {
    private ReferenceRoute referenceRoute;
    private List<ReferenceRouteAttraction> routeAttractions;
}
