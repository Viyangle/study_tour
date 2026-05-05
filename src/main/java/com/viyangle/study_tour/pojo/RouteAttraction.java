package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteAttraction {
    private Long routeId;
    private Integer visitOrder;
    private String poiId;
    private LocalDateTime visitTime;
    private Integer recommendedDuration;
    private String notes;

    // Joined attraction fields
    private String parentPoiId;
    private String name;
    private String address;
    private String location;
    private String pcode;
    private String pname;
    private String citycode;
    private String cityname;
    private String adcode;
    private String adname;
    private String type;
    private String typecode;
    private String distance;
    private String opentimeToday;
    private String opentimeWeek;
    private String tel;
    private LocalDateTime attractionCreatedAt;
    private LocalDateTime attractionUpdatedAt;

    private LocalDateTime createdAt;
}
