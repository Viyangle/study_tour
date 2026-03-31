package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.LoginRequest;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AccountService;
import com.viyangle.study_tour.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
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

    // @Autowired：自动注入 AccountService 对象
    @Autowired
    private AccountService accountService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 健康检查接口（用于测试服务是否正常运行）
     * URL：GET /login/ping
     * 返回：字符串 "ok"
     * 作用：前端或运维人员可以调用这个接口，检查后端服务是否正常
     */
    @GetMapping("/ping")
    public String ping(HttpServletRequest request) {
        String ip = getClientIpAddress(request);
        log.info("ping, 客户端ip: {}", ip);
        return "ok";
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
    @PostMapping
    public Result login(@RequestBody LoginRequest loginRequest) {
        log.info("用户登录，手机号：{}", loginRequest.getPhone());

        Account account = accountService.login(loginRequest);

        if (account != null) {
            // 生成 JWT token 和刷新 token
            String token = jwtUtil.generateToken(account.getId(), account.getRole());
            String refreshToken = jwtUtil.generateRefreshToken(account.getId());
            
            Map<String, Object> data = new HashMap<>();
            data.put("account", account);
            data.put("token", token);
            data.put("refreshToken", refreshToken);
            return Result.success(data);
        }

        return Result.error("手机号或密码错误");
    }

    /**
     * 刷新 token 接口
     * URL：POST /login/refresh
     * 请求参数：refreshToken
     * 流程：
     * 1. 接收前端传来的 refreshToken
     * 2. 验证 refreshToken 是否有效且未过期
     * 3. 如果有效，生成新的 access token 并返回
     * 4. 如果无效，返回错误信息
     */
    @PostMapping("/refresh")
    public Result refreshToken(@RequestParam String refreshToken) {
        log.info("刷新 token");
        
        try {
            // 1. 验证 refresh token 是否有效
            if (!jwtUtil.validateToken(refreshToken, jwtUtil.extractUsername(refreshToken))) {
                return Result.error("刷新 token 无效或已过期");
            }
            
            // 2. 检查 refresh token 的剩余有效期（应该至少还有 1 天以上）
            Date expiration = jwtUtil.extractExpiration(refreshToken);
            long now = System.currentTimeMillis();
            long expTime = expiration.getTime();
            long remainingTime = expTime - now;
            
            // 如果剩余时间少于 24 小时，拒绝刷新，要求重新登录
            if (remainingTime < 86400000) {  // 24 小时 = 86400000 毫秒
                log.warn("Refresh token 即将过期，剩余时间：{} 小时", remainingTime / 3600000);
                return Result.error("刷新 token 即将过期，请重新登录");
            }
            
            // 3. 获取用户信息
            Long accountId = jwtUtil.extractAccountId(refreshToken);
            Account account = accountService.getById(accountId);
            
            if (account == null) {
                return Result.error("用户不存在");
            }
            
            // 4. 生成新的 access token（24 小时有效）
            String newToken = jwtUtil.generateToken(accountId, account.getRole());
            
            // 5. 返回新 token（refresh token 不变，继续使用原来的 7 天有效期）
            Map<String, Object> data = new HashMap<>();
            data.put("token", newToken);
            data.put("refreshToken", refreshToken);
            data.put("expiresIn", 86400);  // access token 有效期（秒）
            data.put("refreshExpiresIn", remainingTime / 1000);  // refresh token 剩余有效期（秒）
            
            log.info("Token 刷新成功：accountId={}, 新 token 有效期 24 小时", accountId);
            return Result.success(data);
            
        } catch (Exception e) {
            log.error("刷新 token 失败：{}", e.getMessage());
            return Result.error("刷新 token 失败：" + e.getMessage());
        }
    }
}
