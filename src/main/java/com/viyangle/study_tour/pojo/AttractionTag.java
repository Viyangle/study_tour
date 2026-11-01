package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 景点-标签关联（知识图谱中"景点→标签"的边）。
 *
 * <p>自 V4 起该边带权重：{@code priorWeight}/{@code priorStrength} 是目录先验，
 * {@code posCount}/{@code negCount} 是用户反馈证据，{@code effectiveWeight} 是数据库
 * 生成列算出的后验权重（0~1）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttractionTag {
    private String poiId;
    private Long tagId;
    private String source;      // "LLM" 或 "MANUAL"
    private LocalDateTime createdAt;

    private Double priorWeight;
    private Double priorStrength;
    private Double posCount;
    private Double negCount;
    private LocalDateTime lastDecayAt;
    private Double effectiveWeight;
    private Integer vectorVersion;
    private LocalDateTime updatedAt;
}
