package com.viyangle.study_tour.agent.dto;

import lombok.Data;

/** 方案中的有序地点，可转换为原 {@code RouteNode}。 */
@Data
public class RouteNode {
    private String poiId;
    private String name;
    private String location;
    private String address;
    private String cityname;
    private String citycode;
    private String adcode;
    private String type;
    private String typecode;
    private String tel;
    private String opentimeToday;
    private String opentimeWeek;
    private Integer visitOrder;
    private String visitTime;
    private Integer recommendedDuration;
    private String notes;
}
