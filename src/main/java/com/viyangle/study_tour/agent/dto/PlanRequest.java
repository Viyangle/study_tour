package com.viyangle.study_tour.agent.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** {@code POST /routes/agent/runs} 的请求体。 */
@Data
@EqualsAndHashCode(callSuper = false)
public class PlanRequest extends StrictPayload {
    private String requestId;
    private String memoryId;
    private Long accountId;
    private String message;
    private List<Turn> history;
    private List<CurrentStop> currentRoute;
    private Constraints constraints;
    private Origin origin;

    public List<Turn> historyOrEmpty() {
        return history == null ? new ArrayList<>() : history;
    }

    public List<CurrentStop> currentRouteOrEmpty() {
        return currentRoute == null ? new ArrayList<>() : currentRoute;
    }
}
