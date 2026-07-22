package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 景点-标签关联（知识图谱中"景点→标签"的边）。
 * 一个景点可以有多个标签（多对多）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttractionTag {
    private String poiId;
    private Long tagId;
    private String source;      // "LLM" 或 "MANUAL"
    private LocalDateTime createdAt;
}
