package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 景点 15 维标签向量的对外视图。
 *
 * <p>{@code weights} 是排序/召回用的有效值（0~1），{@code share} 是归一化后的
 * 主题构成，仅用于展示；{@code dims} 只包含权重 ≥ 0.05 的维度，避免返回 15 个空对象。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttractionTagVector {
    private Integer dimVersion;
    private Integer vectorVersion;
    private LocalDateTime updatedAt;
    private String primary;
    private String primaryName;
    private List<Double> weights;
    private List<Double> share;
    private List<Dim> dims;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Dim {
        private String code;
        private String name;
        private Double weight;
        private Double prior;
        private Double support;
    }
}
