package com.viyangle.study_tour.agent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 每日行程汇总。 */
@Data
public class DaySummary {
    private Integer day;
    private String date;
    private List<ScheduleItem> schedule = new ArrayList<>();
    private Integer totalMinutes;
    private Integer walkingMeters;
    private Double estimatedCost;
    private Integer legCount;
    private String finishTime;
}
