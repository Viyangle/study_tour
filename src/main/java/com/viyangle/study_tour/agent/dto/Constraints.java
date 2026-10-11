package com.viyangle.study_tour.agent.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/** 规划约束；缺省字段由服务端按文档默认值补齐。 */
@Data
@EqualsAndHashCode(callSuper = false)
public class Constraints extends StrictPayload {
    private String city;
    private String date;
    private Integer days;
    private Integer dailyMinutes;
    private String startTime;
    private String finishByTime;
    private Double budget;
    private Integer maxWalkingMeters;
    private List<String> modes;
    private List<String> mustVisit;
    private List<String> avoid;
    private List<String> interests;
    private String group;
    private String accessibility;
    private String pace;
    private Boolean returnToStart;
}
