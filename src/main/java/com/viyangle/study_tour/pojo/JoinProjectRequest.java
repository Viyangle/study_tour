package com.viyangle.study_tour.pojo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JoinProjectRequest {
    /** 当前账号代表的实际参团人数。 */
    @NotNull(message = "参团人数不能为空")
    @Min(value = 1, message = "参团人数必须大于0")
    private Integer representedCount;
}
