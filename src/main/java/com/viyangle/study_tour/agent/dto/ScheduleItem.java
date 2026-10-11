package com.viyangle.study_tour.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 每日行程中的一项：参观项（{@code VisitSchedule}）或休息项（{@code BreakSchedule}）。
 *
 * <p>两者字段不同：休息项带 {@code type=break}，参观项带 {@code poiId/name/learningGoal}。
 * 空值不序列化，因此参观项不会出现 {@code type} 字段，休息项也不会出现地点字段，
 * 与文档中的两个 schema 保持一致。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScheduleItem {
    private String type;
    private String poiId;
    private String name;
    private String arrival;
    private String departure;
    private Integer minutes;
    private String learningGoal;

    public static ScheduleItem visit(String poiId, String name, String arrival, String departure,
                                     Integer minutes, String learningGoal) {
        ScheduleItem item = new ScheduleItem();
        item.poiId = poiId;
        item.name = name;
        item.arrival = arrival;
        item.departure = departure;
        item.minutes = minutes;
        item.learningGoal = learningGoal;
        return item;
    }

    public static ScheduleItem rest(String arrival, String departure, Integer minutes) {
        ScheduleItem item = new ScheduleItem();
        item.type = "break";
        item.arrival = arrival;
        item.departure = departure;
        item.minutes = minutes;
        return item;
    }
}
