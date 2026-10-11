package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 领队放弃带队的审计记录。
 *
 * <p>保存项目、领队、原因、放弃前的项目状态与操作时间，用于后续核对
 * 「谁在什么时候以什么理由放弃了哪个项目」。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectAbandonRecord {
    private Long id;
    private Long projectId;
    private Long leaderAccountId;
    private String reason;
    private String previousStatus;
    private LocalDateTime departureAt;
    private LocalDateTime abandonedAt;
    private LocalDateTime createdAt;
}
