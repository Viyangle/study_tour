package com.viyangle.study_tour.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 工具事件；最早的“理解需求”事件可能没有 tool，运行中事件可能没有 elapsedMs。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolEvent {
    private String tool;
    private String label;
    private String status;
    private Integer elapsedMs;

    public static ToolEvent of(String tool, String label, String status, Integer elapsedMs) {
        return new ToolEvent(tool, label, status, elapsedMs);
    }
}
