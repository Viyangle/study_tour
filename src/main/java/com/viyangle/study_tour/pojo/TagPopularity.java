package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 景点近 30 天曝光去重用户数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagPopularity {
    private String poiId;
    private Integer userCount;
}
