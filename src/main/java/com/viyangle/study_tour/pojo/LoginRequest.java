package com.viyangle.study_tour.pojo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录请求DTO
 * 作用：专门用来接收前端传来的登录表单数据
 * 为什么需要这个类：登录只需要手机号和密码，不需要 Account 对象里的其他字段（如 id、role 等）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;      // 手机号（用于登录）

    @NotBlank(message = "密码不能为空")
    private String password;   // 密码
}




