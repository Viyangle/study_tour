package com.viyangle.study_tour.pojo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Route {
    private Long id;
    private String regionAdcode;
    private String tag;
    private Boolean containsOutdatedAttractions;
    private LocalDateTime createdAt;

    /** 多标签路线向量；列表/详情响应填充，数据库不存该字段。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AttractionTagVector tagVector;
}
