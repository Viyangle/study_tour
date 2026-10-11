package com.viyangle.study_tour.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Account {
    private Long id;
    private String role;
    private String username;
    private String phone;
    @JsonIgnore
    private String passwordHash;
    private String regionCode;
    private String avatarUrl;
    private String intro;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
