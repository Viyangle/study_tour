package com.viyangle.study_tour.pojo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 注册请求 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名不能超过50个字符")
    private String username;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需为6-64个字符")
    private String password;

    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;

    @NotBlank(message = "角色不能为空")
    @Pattern(regexp = "(?i)^(USER|LEADER|BOTH)$", message = "注册仅支持USER/LEADER/BOTH角色")
    private String role;

    @NotBlank(message = "regionCode不能为空")
    private String regionCode;
}

