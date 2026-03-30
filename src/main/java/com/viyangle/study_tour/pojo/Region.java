package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Region {
    private String adcode;
    private String name;
    private Integer level;
    private String parentAdcode;
    private String citycode;
    private Integer isVirtual;
    private Integer hasChildren;
}
