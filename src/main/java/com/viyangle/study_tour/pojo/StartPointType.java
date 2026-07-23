package com.viyangle.study_tour.pojo;

public enum StartPointType {
    CURRENT_LOCATION,
    MANUAL;

    public static StartPointType from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("起点类型不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("起点类型仅支持CURRENT_LOCATION或MANUAL");
        }
    }
}
