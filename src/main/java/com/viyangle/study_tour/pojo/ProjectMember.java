package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMember {
    private Long id;
    private Long projectId;
    private Long accountId;
    private String joinStatus;
    private LocalDateTime joinedAt;
}
