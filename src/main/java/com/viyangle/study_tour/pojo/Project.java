package com.viyangle.study_tour.pojo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Project {
    private Long id;
    private Long routeId;
    private String regionAdcode;
    private String tag;
    private Long ownerAccountId;
    private Long leaderAccountId;
    private String title;
    private LocalDate departureDate;
    private LocalTime departureTime;
    private String startPointType;
    private String startPoint;
    private String leaderRequirements;
    private String participantRequirements;
    private Integer maxMembers;
    private Integer currentMembers;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 创建订单时，当前账号实际代表的参团人数；不属于 projects 表。 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Integer representedCount;

    public String getStatusText() {
        return ProjectStatus.displayNameOf(status);
    }
}
