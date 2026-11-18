package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户-标签长期偏好；只参与该用户的排序，不写回景点先验。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountTagAffinity {
    private Long accountId;
    private Long tagId;
    private Double affinity;
    private LocalDateTime updatedAt;
}
