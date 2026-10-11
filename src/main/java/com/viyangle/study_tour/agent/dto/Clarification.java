package com.viyangle.study_tour.agent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** {@code result.kind=clarification}：追问是一次成功完成的对话，不是任务失败。 */
@Data
public class Clarification {
    private String kind = "clarification";
    private String message;
    private List<String> questions = new ArrayList<>();
    private List<RouteNode> nodes = new ArrayList<>();
    private List<Leg> legs = new ArrayList<>();
    private Constraints constraints;
}
