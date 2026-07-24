package com.viyangle.study_tour.pojo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

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

    /** 创建项目时，当前账号实际代表的参团人数；不属于 projects 表。 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Integer representedCount;

    /*
     * Response-only fields used by project lists and details. These are not
     * columns in the projects table.
     */
    private String publisherName;
    private String publisherAvatarUrl;
    private String leaderName;
    private String leaderAvatarUrl;
    private List<String> attractionNames;
    private List<RouteAttraction> routeAttractions;
    private Integer estimatedDurationMinutes;
    private String availabilityStatus;
    private String viewerRole;
    private Boolean canAccept;
    private Boolean canJoin;
    private Boolean canManageGroup;
    private Long groupId;

    public String getStatusText() {
        return ProjectStatus.displayNameOf(status);
    }
}
