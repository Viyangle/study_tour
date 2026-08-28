package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 景点相邻关系（知识图谱中"景点→景点"的相邻边）。
 * 两个景点之间公交通勤时间在阈值内（如30分钟）即视为相邻。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttractionAdjacency {
    private String fromPoiId;
    private String toPoiId;
    private Integer transitMinutes;  // 公交通勤时间(分钟)，null 表示无公交
    private Integer distanceM;       // 交通距离(米)
    private String relationType;     // GEOGRAPHIC / THEMATIC
    private Double similarityScore;  // 语义相似度(0-1)，仅 THEMATIC 有值
    private LocalDateTime createdAt;
}
