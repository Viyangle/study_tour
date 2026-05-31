package com.viyangle.study_tour.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.annotation.OperationLog;
import com.viyangle.study_tour.annotation.RequireRole;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.LeaderProfile;
import com.viyangle.study_tour.pojo.Result;
import com.viyangle.study_tour.pojo.UpdateAccountIntroRequest;
import com.viyangle.study_tour.pojo.UpdateAccountProfileRequest;
import com.viyangle.study_tour.pojo.UpdateAccountRoleRequest;
import com.viyangle.study_tour.pojo.UpdatePasswordRequest;
import com.viyangle.study_tour.service.AccountService;
import com.viyangle.study_tour.utils.JwtUtil;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/accounts")
public class AccountController {

    @Autowired
    private AccountService accountService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    private static final Set<String> PROFILE_FORBIDDEN_FIELDS = Set.of(
            "id", "phone", "role", "intro", "password", "passwordHash", "createdAt", "updatedAt"
    );

    @GetMapping("/{id}")
    @OperationLog(value = "获取用户详情", type = "USER_QUERY")
    public Result getById(@PathVariable Long id) {
        log.info("获取用户：{}", id);
        return Result.success(accountService.getById(id));
    }

    @GetMapping("/{id}/tagPrefs")
    @OperationLog(value = "获取用户标签偏好", type = "USER_QUERY")
    public Result getTagPrefs(@PathVariable Long id) {
        log.info("获取用户标签偏好：{}", id);
        return Result.success(accountService.getTagPrefs(id));
    }

    @GetMapping("/{id}/leaderProfile")
    @OperationLog(value = "获取领队资料", type = "LEADER_QUERY")
    public Result getLeaderProfile(@PathVariable Long id) {
        log.info("获取领队简介：{}", id);
        return Result.success(accountService.getLeaderProfile(id));
    }

    @PutMapping("/{id}")
    @OperationLog(value = "修改个人信息", type = "USER_UPDATE")
    @RequireRole({"USER", "LEADER", "ADMIN"})
    public Result updateProfile(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        log.info("修改个人信息：{}", id);
        validateProfileUpdateFields(request);
        UpdateAccountProfileRequest updateRequest = objectMapper.convertValue(request, UpdateAccountProfileRequest.class);
        return Result.success(accountService.updateProfile(id, updateRequest));
    }

    @PutMapping("/{id}/role")
    @OperationLog(value = "修改用户角色", type = "USER_UPDATE")
    @RequireRole({"USER", "LEADER", "ADMIN"})
    public Result updateRole(@PathVariable Long id, @RequestBody UpdateAccountRoleRequest request) {
        log.info("修改用户角色：{}", id);
        Account account = accountService.updateRole(id, request == null ? null : request.getRole());
        Map<String, Object> data = new HashMap<>();
        data.put("account", account);
        if (id.equals(SecurityContextUtil.currentAccountId())) {
            data.put("token", jwtUtil.generateToken(account.getId(), account.getRole()));
            data.put("refreshToken", jwtUtil.generateRefreshToken(account.getId()));
        }
        return Result.success(data);
    }

    @PutMapping("/{id}/password")
    @OperationLog(value = "修改用户密码", type = "USER_UPDATE")
    @RequireRole({"USER", "LEADER", "ADMIN"})
    public Result updatePassword(@PathVariable Long id, @RequestBody UpdatePasswordRequest request) {
        log.info("修改用户密码：{}", id);
        accountService.updatePassword(id, request);
        return Result.success();
    }

    @PutMapping("/{id}/userIntro")
    @OperationLog(value = "修改普通用户简介", type = "USER_UPDATE")
    @RequireRole({"USER", "ADMIN"})
    public Result updateUserIntro(@PathVariable Long id, @RequestBody UpdateAccountIntroRequest request) {
        log.info("修改普通用户简介：{}", id);
        return Result.success(accountService.updateUserIntro(id, request == null ? null : request.getIntro()));
    }

    @PostMapping("/{id}/tagPrefs")
    @OperationLog(value = "修改用户标签偏好", type = "USER_UPDATE")
    @RequireRole({"USER", "LEADER"})
    public Result changeTagPrefs(@PathVariable Long id, @RequestBody List<AccountTagPref> accountTagPrefs) {
        log.info("修改用户标签偏好：{}", id);
        accountService.changeTagPrefs(id, accountTagPrefs);
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

    private void validateProfileUpdateFields(Map<String, Object> request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        for (String field : PROFILE_FORBIDDEN_FIELDS) {
            if (request.containsKey(field)) {
                if ("phone".equals(field)) {
                    throw new IllegalArgumentException("手机号暂不支持修改");
                }
                if ("role".equals(field)) {
                    throw new IllegalArgumentException("角色请使用/accounts/{id}/role接口修改");
                }
                if ("intro".equals(field)) {
                    throw new IllegalArgumentException("普通用户简介请使用/accounts/{id}/userIntro接口修改");
                }
                throw new IllegalArgumentException("字段不支持修改: " + field);
            }
        }
    }
}
