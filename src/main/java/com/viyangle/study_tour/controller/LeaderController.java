package com.viyangle.study_tour.controller;

import com.viyangle.study_tour.annotation.OperationLog;
import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.service.LeaderService;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/leader")
public class LeaderController {

    @Autowired
    private LeaderService leaderService;

    @GetMapping("/orders")
    @OperationLog(value = "查询领队可接订单", type = "PROJECT_QUERY")
    @RequireRole({"LEADER"})
    public Result getAvailableOrders(@RequestParam(defaultValue = "1") Integer pageNum,
                                     @RequestParam(defaultValue = "10") Integer pageSize) {
        Long accountId = SecurityContextUtil.currentAccountId();
        log.info("查询领队可接订单: accountId={}, pageNum={}, pageSize={}", accountId, pageNum, pageSize);
        return Result.success(leaderService.getAvailableOrders(accountId, pageNum, pageSize));
    }

    @GetMapping("/orders/{projectId}")
    @OperationLog(value = "查询领队订单详情", type = "PROJECT_QUERY")
    @RequireRole({"LEADER"})
    public Result getOrderDetail(@PathVariable Long projectId) {
        Long accountId = SecurityContextUtil.currentAccountId();
        log.info("查询领队订单详情: accountId={}, projectId={}", accountId, projectId);
        return Result.success(leaderService.getOrderDetail(accountId, projectId));
    }

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
