package com.viyangle.study_tour.pojo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RouteConstraintState {
    private List<String> userMessages = new ArrayList<>();
    private List<String> excludePoiIds = new ArrayList<>();
    private List<String> excludeNameKeywords = new ArrayList<>();
    private List<String> lastRoutePoiIds = new ArrayList<>();
    private String lastUserMessage;
}
