package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReferencePair {
    private Long id;
    private String tag;
    private String notes;
    private String regionAdcode;
    private String fromPoiId;
    private String fromPoiName;
    private String toPoiId;
    private String toPoiName;
    private LocalDateTime createdAt;
}
