package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一次曝光：哪个景点在什么场景、第几位被展示给哪个用户。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagImpression {
    private Long id;
    private Long accountId;
    private String sessionKey;
    private String scene;
    private String poiId;
    private Integer position;
    private String modelVersion;
    private LocalDateTime shownAt;
}
