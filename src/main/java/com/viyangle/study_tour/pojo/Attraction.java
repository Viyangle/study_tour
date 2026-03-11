package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Attraction {
    private Long id;
    private String name;
    private String type;
    private String location;
    private String regionCode;
    private String description;
    private Integer recommendedDuration;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
