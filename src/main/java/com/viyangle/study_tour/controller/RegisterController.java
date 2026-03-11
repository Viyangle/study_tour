package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/register")
public class RegisterController {

    @Autowired
    private AccountService accountService;
    @PostMapping
    public Result register(@RequestBody Account account){
        log.info("用户注册, {}", account);
        Long id = accountService.register(account);

        if (id == -1) {
            return Result.error("用户名已存在");
        }
        if (id == -2) {
            return Result.error("手机号已存在");
        }

        return Result.success();
    }
}
