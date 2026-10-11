package com.viyangle.study_tour.agent.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 一轮对话记录；role 只允许 user / assistant。 */
@Data
@EqualsAndHashCode(callSuper = false)
public class Turn extends StrictPayload {
    private String role;
    private String content;
}
