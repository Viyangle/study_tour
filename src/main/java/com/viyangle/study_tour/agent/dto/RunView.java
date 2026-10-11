package com.viyangle.study_tour.agent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 任务视图；查询与创建、取消使用同一结构。 */
@Data
public class RunView {
    private String runId;
    private String status;
    private String stage;
    private List<ToolEvent> events = new ArrayList<>();
    private Object result;
    private String error;
}
