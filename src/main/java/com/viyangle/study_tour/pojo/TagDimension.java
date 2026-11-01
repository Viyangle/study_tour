package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标签向量维度定义（对应 tag_dimension 表，name 来自 tags 联查）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagDimension {
    private Integer dimVersion;
    private Integer ordinal;
    private Long tagId;
    private String code;
    private String name;
    private String description;
    private Boolean enabled;
}
