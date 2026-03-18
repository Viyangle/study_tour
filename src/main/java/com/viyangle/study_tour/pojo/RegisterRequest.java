package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 注册请求DTO
 * 作用：专门用来接收前端传来的注册表单数据
 * 为什么需要这个类：因为注册时需要 confirmPassword（确认密码）字段，
 * 但数据库的 Account 表里不需要存这个字段，所以单独创建一个请求对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {
    private String username;        // 用户名（允许重复）
    private String phone;          // 手机号（唯一，用于登录）
    private String password;       // 密码
    private String confirmPassword;// 确认密码（用于前端校验两次输入是否一致）
    private String role;           // 角色：USER 或 LEADER
}




