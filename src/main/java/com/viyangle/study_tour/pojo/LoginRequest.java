package com.viyangle.study_tour.pojo;

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
    private String phone;      // 手机号（用于登录）
    private String password;   // 密码
}




