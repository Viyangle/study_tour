package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 景点-标签全局学习层，与 {@link AttractionTag} 的语义先验分开存。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttractionTagLearned {
    private String poiId;
    private Long tagId;
    private Double posCount;
    private Double negCount;
    private Integer distinctUsers;
    private LocalDateTime lastDecayAt;
    private Integer vectorVersion;
    private LocalDateTime updatedAt;
}
