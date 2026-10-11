package com.viyangle.study_tour.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 主动拒绝请求的响应体：{@code {"error": "..."}}。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentError {
    private String error;
}
