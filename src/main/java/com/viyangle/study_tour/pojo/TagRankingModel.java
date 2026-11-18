package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * pairwise 排序模型：{@code coefficients} 是特征名到 JSON 数值的映射。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagRankingModel {
    private String modelVersion;
    private String coefficients;
    private Double intercept;
    private Integer sampleCount;
    private Boolean active;
    private LocalDateTime trainedAt;
}
