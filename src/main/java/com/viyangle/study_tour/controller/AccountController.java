package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.LeaderProfile;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/account")
public class AccountController {

    @Autowired
    private AccountService accountService;
    @PostMapping("/tagPrefs")
    public Result changeTagPrefs(@RequestBody List<AccountTagPref> accountTagPrefs) {
        log.info("修改用户标签偏好");
        accountService.changeTagPrefs(accountTagPrefs);
        return Result.success();
    }
    @PostMapping("/intro")
    public Result changeIntro(@RequestBody LeaderProfile leaderProfile) {
        log.info("修改领队简介");
        accountService.changeIntro(leaderProfile.getAccountId(), leaderProfile.getIntro());
        return Result.success();
    }
}
