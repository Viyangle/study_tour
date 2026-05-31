package com.viyangle.study_tour.pojo;

import lombok.Data;

@Data
public class UpdateAccountProfileRequest {
    private String username;
    private String regionCode;
    private String avatarUrl;
    private Integer status;
}
