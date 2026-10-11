package com.viyangle.study_tour.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code GET /health} 响应体。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Health {
    private String status;
    private String version;
}
