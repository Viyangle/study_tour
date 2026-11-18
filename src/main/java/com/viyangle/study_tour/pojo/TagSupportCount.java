package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * distinct_users 聚合查询的临时结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagSupportCount {
    private Long tagId;
    private Integer userCount;
}
