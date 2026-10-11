package com.viyangle.study_tour.agent.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 客户端授权定位得到的坐标；经纬度沿用高德 SDK 取值。 */
@Data
@EqualsAndHashCode(callSuper = false)
public class Origin extends StrictPayload {
    private Double longitude;
    private Double latitude;
}
