package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.RegisterRequest;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AccountService;
import com.viyangle.study_tour.utils.PhoneValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/register")
public class RegisterController {

    @Autowired
    private AccountService accountService;

    @PostMapping
    public Result register(@RequestBody RegisterRequest registerRequest) {
        log.info("User register, phone: {}, username: {}, regionCode: {}",
                registerRequest.getPhone(), registerRequest.getUsername(), registerRequest.getRegionCode());

        if (!StringUtils.hasText(registerRequest.getRegionCode())) {
            return Result.error("regionCode不能为空");
        }

        if (!PhoneValidator.isValidChineseMainlandMobile(registerRequest.getPhone())) {
            return Result.error("手机号格式不正确");
        }

        if (!Objects.equals(registerRequest.getPassword(), registerRequest.getConfirmPassword())) {
            return Result.error("两次密码不一致");
        }

        Long id = accountService.register(registerRequest);
        if (id == -1L) {
            return Result.error("手机号已存在");
        }

        return Result.success();
    }
}
