package com.viyangle.study_tour.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 客户端当前路线中的一个地点。
 *
 * <p>文档允许该对象携带原 {@code RouteNode} 的其他字段，服务端忽略它们，
 * 只使用 {@code poiId} 重新核验详情，因此这里显式忽略未知字段。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CurrentStop {
    private String poiId;
    private String name;
    private Integer recommendedDuration;
    private String notes;
}
