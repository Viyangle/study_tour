package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.RegisterRequest;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RegisterController 类
 * 作用：处理用户注册相关的 HTTP 请求
 * &#064;RestController：告诉  Spring 这是一个 REST 风格的控制器，返回的数据会自动转成 JSON
 * &#064;RequestMapping("/register")：这个类下的所有接口的  URL 都以 /register 开头
 * 这个类负责：
 * - 接收前端的注册请求
 * - 校验请求数据（比如两次密码是否一致）
 * - 调用 Service 层完成注册
 * - 返回注册结果给前端
 */
@Slf4j  // Lombok 注解，自动生成 log 对象，可以用 log.info() 打印日志
@RestController
@RequestMapping("/register")
public class RegisterController {

    // @Autowired：自动注入 AccountService 对象
    // Spring 会在容器中找到 AccountServiceImpl 的实例，然后赋值给这个变量
    @Autowired
    private AccountService accountService;

    /**
     * 注册接口
     * URL：POST /register
     * 请求体：JSON 格式
     * {
     *   "username": "张三",
     *   "phone": "13800138000",
     *   "password": "123456",
     *   "confirmPassword": "123456",
     *   "role": "USER" 或 "LEADER"
     * }
     * 流程：
     * 1. 接收前端传来的 RegisterRequest 对象（Spring 自动把 JSON 转成对象）
     * 2. 校验两次密码是否一致
     * 3. 调用 Service 层的 register 方法
     * 4. 根据返回值判断注册结果，返回相应的 Result 对象
     */
    @PostMapping  // 表示这是一个 POST 请求，完整 URL 是 /register
    public Result register(@RequestBody RegisterRequest registerRequest) {
        // 打印日志：记录谁在注册（方便调试和排查问题）
        log.info("用户注册, 手机号: {}, 用户名: {}", registerRequest.getPhone(), registerRequest.getUsername());

        // 1. 校验两次密码是否一致
        if (!registerRequest.getPassword().equals(registerRequest.getConfirmPassword())) {
            // 如果两次密码不一致，直接返回错误信息
            return Result.error("两次密码不一致");
        }

        // 2. 调用 Service 层的注册方法
        // register 方法会检查用户名和手机号是否重复
        // 返回值：成功返回账号 id，失败返回 -1（手机号已存在）或 -2（用户名已存在）
        Long id = accountService.register(registerRequest);

        // 3. 根据返回值判断注册结果
        if (id == -2) {
            // 返回 -2 表示用户名已存在
            return Result.error("用户名已存在");
        }
        if (id == -1) {
            // 返回 -1 表示手机号已存在
            return Result.error("手机号已存在");
        }

        // 4. 注册成功，返回成功信息
        // Result.success() 会返回 {code: 1, msg: "success", data: null}
        return Result.success();
    }
}
