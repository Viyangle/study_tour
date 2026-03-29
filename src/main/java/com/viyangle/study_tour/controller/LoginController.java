package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import com.viyangle.study_tour.utils.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * LoginController 类
 * 作用：处理用户登录相关的 HTTP 请求
 * &#064;RestController：告诉  Spring 这是一个 REST 风格的控制器，返回的数据会自动转成 JSON
 * &#064;RequestMapping("/login")：这个类下的所有接口的  URL 都以 /login 开头
 * 这个类负责：
 * - 接收前端的登录请求
 * - 调用 Service 层验证用户名和密码
 * - 返回登录结果给前端（成功返回用户信息，失败返回错误信息）
 */
@Slf4j  // Lombok 注解，自动生成 log 对象，可以用 log.info() 打印日志
@RestController
@RequestMapping("/login")
public class LoginController {

    @Autowired
    private AccountService accountService;

    @Autowired
    private JwtUtil jwtUtil;

    @GetMapping("/ping")
    public String ping(HttpServletRequest request) {
        String ip = getClientIpAddress(request);
        log.info("ping, 客户端ip: {}", ip);
    }


    private String getClientIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // 如果是多个 IP（经过多个代理），取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip;
    }

    @PostMapping
    public Result login(@RequestBody Account account){
        log.info("用户登录, {}", account);
        //TODO: BCrypt

    /**
     * 登录接口
     * URL：POST /login
     * 请求体：JSON 格式
     * {
     *   "phone": "13800138000",
     *   "password": "123456"
     * }
     * 流程：
     * 1. 接收前端传来的 LoginRequest 对象（Spring 自动把 JSON 转成对象）
     * 2. 调用 Service 层的 login 方法验证手机号和密码
     * 3. 如果登录成功，返回用户信息；如果失败，返回错误信息
     * 注意：
     * - 目前密码是明文比较，未来应该用 BCrypt 加密后比较
     * - 目前没有生成 JWT token，未来应该返回 token 给前端
     */
    @PostMapping  // POST 请求，完整 URL 是 /login
    public Result login(@RequestBody LoginRequest loginRequest) {
        log.info("用户登录, 手机号: {}", loginRequest.getPhone());

        Account account = accountService.login(loginRequest);

        if (account != null) {
            // 生成JWT token
            String token = jwtUtil.generateToken(account.getId().toString());
            Map<String, Object> data = new HashMap<>();
            data.put("account", account);
            data.put("token", token);
            return Result.success(data);
        }

        return Result.error("手机号或密码错误");
    }
}
