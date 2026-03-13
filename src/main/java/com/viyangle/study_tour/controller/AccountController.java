package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.pojo.Account;
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
@RequestMapping("/accounts")
public class AccountController {

    @Autowired
    private AccountService accountService;
    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id){
        log.info("获取用户: {}", id);
        return Result.success(accountService.getById(id));
    }

    @GetMapping("/{id}/tagPrefs")
    public Result getTagPrefs(@PathVariable Long id){
        log.info("获取用户标签偏好: {}", id);
        return Result.success(accountService.getTagPrefs(id));
    }

    @GetMapping("/{id}/leaderProfile")
    public Result getLeaderProfile(@PathVariable Long id){
        log.info("获取领队简介: {}", id);
        return Result.success(accountService.getLeaderProfile(id));
    }
    @PostMapping("/{id}/tagPrefs")
    public Result changeTagPrefs(@PathVariable Long id, @RequestBody List<AccountTagPref> accountTagPrefs) {
        log.info("修改用户标签偏好: {}", id);
        accountService.changeTagPrefs(accountTagPrefs);
        return Result.success();
    }
    @PostMapping("/{id}/intro")
    public Result changeIntro(@PathVariable Long id, @RequestBody LeaderProfile leaderProfile) {
        log.info("修改领队简介: {}", id);
        accountService.changeIntro(id, leaderProfile.getIntro());
        return Result.success();
    }
    @PostMapping("/{id}/avatar")
    public Result changeAvatar(@PathVariable Long id, @RequestBody Account account) {
        log.info("修改用户头像: {}", id);
        account.setId(id);
        accountService.changeAvatar(account);
        return Result.success();
    }
}
