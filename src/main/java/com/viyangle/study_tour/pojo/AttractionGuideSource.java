package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已通过景点相关性校验的讲解资料。text 只作为 AI 加工输入，不直接展示给用户。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttractionGuideSource {
    private String sourceType;
    private String sourceName;
    private String sourceUrl;
    private String text;
    private String imageUrl;
    private String matchedTitle;
    private String revision;
    private String license;
    private String attribution;
    private boolean parentSource;
}
