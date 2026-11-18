package com.viyangle.study_tour.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttractionGuide {
    private String poiId;
    private String name;
    private String address;
    private String summary;
    private String content;
    private List<String> studyPoints;
    private List<String> questions;
    private String visitTips;
    private String coverUrl;
    private String audioUrl;
    private String sourceName;
    private String sourceUrl;
    private String sourceType;
    private String sourceRevision;
    private String sourceLicense;
    private String sourceAttribution;
    private String syncStatus;
    private LocalDateTime syncedAt;
    private LocalDateTime updatedAt;

    /** 以下来源快照和同步控制字段仅供自动更新链路使用。 */
    @JsonIgnore
    private String sourceText;
    @JsonIgnore
    private String sourceHash;
    @JsonIgnore
    private String syncError;
    @JsonIgnore
    private Boolean autoUpdate;
    @JsonIgnore
    private String status;
}
