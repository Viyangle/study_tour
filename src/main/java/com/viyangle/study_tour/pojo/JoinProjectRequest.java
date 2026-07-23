package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JoinProjectRequest {
    /** 当前账号代表的实际参团人数。 */
    private Integer representedCount;
}
