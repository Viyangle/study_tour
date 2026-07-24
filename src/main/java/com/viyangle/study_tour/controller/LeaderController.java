package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.annotation.OperationLog;
import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.LeaderService;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/leader")
public class LeaderController {

    @Autowired
    private LeaderService leaderService;

    @GetMapping("/profile")
    @OperationLog(value = "查询领队资料看板", type = "LEADER_QUERY")
    @RequireRole({"LEADER"})
    public Result getProfile() {
        Long accountId = SecurityContextUtil.currentAccountId();
        log.info("查询领队资料看板: accountId={}", accountId);
        return Result.success(leaderService.getProfile(accountId));
    }

    @GetMapping("/reviews")
    @OperationLog(value = "查询领队收到的评价", type = "REVIEW_QUERY")
    @RequireRole({"LEADER"})
    public Result getReviews(@RequestParam(defaultValue = "1") Integer pageNum,
                             @RequestParam(defaultValue = "20") Integer pageSize) {
        Long accountId = SecurityContextUtil.currentAccountId();
        log.info("查询领队评价: accountId={}, pageNum={}, pageSize={}", accountId, pageNum, pageSize);
        return Result.success(leaderService.getReviews(accountId, pageNum, pageSize));
    }
}
