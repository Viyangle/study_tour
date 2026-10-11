package com.viyangle.study_tour.agent.dto;

import lombok.Data;

import java.util.List;

/** 一段独立的交通段；不能把不同天或不连续段强行连接。 */
@Data
public class Leg {
    private Integer day;
    private String origin;
    private String destination;
    private String originName;
    private String destinationName;
    private String mode;
    private Integer minutes;
    private Integer distanceMeters;
    private Integer walkingMeters;
    private Double cost;
    private List<List<Double>> polyline;
    private List<String> instructions;
    private String source;
    private String note;
}
