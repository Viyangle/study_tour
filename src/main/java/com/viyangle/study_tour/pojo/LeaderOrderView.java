package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Read model used by the leader order list and order detail screens.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaderOrderView {
    private Long id;
    private Long routeId;
    private Long ownerAccountId;
    private String customerName;
    private String customerAvatarUrl;
    private String title;
    private LocalDate departureDate;
    private LocalTime departureTime;
    private String startPointType;
    private String startPoint;
    private String leaderRequirements;
    private String participantRequirements;
    private List<String> attractionNames;
    private List<RouteAttraction> routeAttractions;
    private Integer estimatedDurationMinutes;
    private String tag;
    private Integer peopleCount;
    private Integer maxMembers;
    private String projectStatus;
    private String orderStatus;
    private Boolean canAccept;
}
