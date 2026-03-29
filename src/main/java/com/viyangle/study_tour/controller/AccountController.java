package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.annotation.OperationLog;
import com.viyangle.study_tour.annotation.RequireRole;
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
    @OperationLog(value = "获取用户详情", type = "USER_QUERY")
    public Result getById(@PathVariable Long id){
        log.info("获取用户：{}", id);
        return Result.success(accountService.getById(id));
    }

    @GetMapping("/{id}/tagPrefs")
    @OperationLog(value = "获取用户标签偏好", type = "USER_QUERY")
    public Result getTagPrefs(@PathVariable Long id){
        log.info("获取用户标签偏好：{}", id);
        return Result.success(accountService.getTagPrefs(id));
    }

    @GetMapping("/{id}/leaderProfile")
    @OperationLog(value = "获取领队资料", type = "LEADER_QUERY")
    public Result getLeaderProfile(@PathVariable Long id){
        log.info("获取领队简介：{}", id);
        return Result.success(accountService.getLeaderProfile(id));
    }
    @PostMapping("/{id}/tagPrefs")
    @OperationLog(value = "修改用户标签偏好", type = "USER_UPDATE")
    @RequireRole({"USER", "LEADER"})
    public Result changeTagPrefs(@PathVariable Long id, @RequestBody List<AccountTagPref> accountTagPrefs) {
        log.info("修改用户标签偏好：{}", id);
        accountService.changeTagPrefs(accountTagPrefs);
        return Result.success();
    }
    @PostMapping("/{id}/intro")
    @OperationLog(value = "修改领队简介", type = "LEADER_UPDATE")
    @RequireRole({"LEADER"})
    public Result changeIntro(@PathVariable Long id, @RequestBody LeaderProfile leaderProfile) {
        log.info("修改领队简介：{}", id);
        accountService.changeIntro(id, leaderProfile.getIntro());
        return Result.success();
    }
}
