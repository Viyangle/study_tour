package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/login")
public class LoginController {

    @Autowired
    private AccountService accountService;
    @GetMapping("/ping")
    public String ping() {
        return "ok";
    }
    @PostMapping
    public Result login(@RequestBody Account account){
        log.info("用户登录, {}", account);
        //TODO: BCrypt

        Account a = accountService.login(account);

        if (a != null){
            //TODO: jwt token

            return Result.success(a);
        }

        return Result.error("用户名或密码错误");
    }
}
