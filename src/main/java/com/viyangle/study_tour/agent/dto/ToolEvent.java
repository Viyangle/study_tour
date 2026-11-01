package com.viyangle.study_tour.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 进度事件；理解需求与模型阶段说明的 tool 为 null，运行中事件可能没有 elapsedMs。 */
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
