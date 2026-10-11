package com.viyangle.study_tour.agent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** {@code result.kind=plan} 的完成方案。 */
@Data
public class AgentPlan {
    private String kind = "plan";
    private String message;
    private List<String> assumptions = new ArrayList<>();
    private Constraints constraints;
    private Boolean feasible = Boolean.TRUE;
    private List<String> issues = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private List<RouteNode> nodes = new ArrayList<>();
    private List<DaySummary> days = new ArrayList<>();
    private List<Leg> legs = new ArrayList<>();
    private Double estimatedCost = 0.0;
    private Integer unknownCosts = 0;
    private List<String> addedPoiIds = new ArrayList<>();
    private List<String> removedPoiIds = new ArrayList<>();
    private Boolean requiresAcceptance = Boolean.TRUE;
}
