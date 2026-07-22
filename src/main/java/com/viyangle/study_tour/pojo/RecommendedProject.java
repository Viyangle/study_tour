package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 带解释的推荐项目结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendedProject {
    private Project project;
    private double score;
    /** 推荐理由文案列表 */
    private List<String> reasons;
}
