package com.viyangle.study_tour.pojo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdateAccountRoleRequest {

    @NotBlank(message = "角色不能为空")
    @Pattern(regexp = "(?i)^(USER|LEADER|BOTH|ADMIN)$", message = "角色仅支持USER/LEADER/BOTH/ADMIN")
    private String role;
}
