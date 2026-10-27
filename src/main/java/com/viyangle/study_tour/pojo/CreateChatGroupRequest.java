package com.viyangle.study_tour.pojo;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateChatGroupRequest {
    @NotNull(message = "项目ID不能为空")
    private Long projectId;
}
