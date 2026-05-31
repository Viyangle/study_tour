package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.ResourceNotFoundException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.LeaderProfileMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.LeaderProfile;
import com.viyangle.study_tour.pojo.LoginRequest;
import com.viyangle.study_tour.pojo.RegisterRequest;
import com.viyangle.study_tour.pojo.UpdateAccountProfileRequest;
import com.viyangle.study_tour.pojo.UpdatePasswordRequest;
import com.viyangle.study_tour.service.AccountService;
import com.viyangle.study_tour.utils.PhoneValidator;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
public class AccountServiceImpl implements AccountService {
    private static final Set<String> USER_EDITABLE_ROLES = Set.of("USER", "LEADER", "BOTH");
    private static final Set<String> ALL_ROLES = Set.of("USER", "LEADER", "BOTH", "ADMIN");

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private AccountTagPrefMapper accountTagPrefMapper;

    @Autowired
    private LeaderProfileMapper leaderProfileMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Override
    public Account login(LoginRequest loginRequest) {
        if (loginRequest == null || !PhoneValidator.isValidChineseMainlandMobile(loginRequest.getPhone())) {
            return null;
        }
        Account account = accountMapper.selectByPhone(loginRequest.getPhone());
        if (account != null && passwordEncoder.matches(loginRequest.getPassword(), account.getPasswordHash())) {
            return account;
        }
        return null;
    }

    @Override
    public Long register(RegisterRequest registerRequest) {
        if (registerRequest == null || !PhoneValidator.isValidChineseMainlandMobile(registerRequest.getPhone())) {
            throw new IllegalArgumentException("手机号格式不正确");
        }
        if (accountMapper.existsByPhone(registerRequest.getPhone())) {
            return -1L;
        }

        Account account = new Account();
        account.setRole(registerRequest.getRole());
        account.setUsername(registerRequest.getUsername());
        account.setPhone(registerRequest.getPhone());
        account.setPasswordHash(passwordEncoder.encode(registerRequest.getPassword()));
        String regionCode = registerRequest.getRegionCode() == null ? null : registerRequest.getRegionCode().trim();
        account.setRegionCode(regionCode);
        account.setIntro(null);
        account.setStatus(1);
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());

        accountMapper.insert(account);

        if (hasLeaderPermission(account.getRole())) {
            LeaderProfile leaderProfile = new LeaderProfile(account.getId(), null, null, null);
            leaderProfileMapper.insert(leaderProfile);
        }

        return account.getId();
    }

    @Override
    public void changeTagPrefs(Long pathAccountId, List<AccountTagPref> accountTagPrefs) {
        requireSelfOrAdmin(pathAccountId);

        List<AccountTagPref> prefs = accountTagPrefs == null ? Collections.emptyList() : accountTagPrefs;
        accountTagPrefMapper.deleteByAccountId(pathAccountId);

        for (AccountTagPref accountTagPref : prefs) {
            if (accountTagPref == null || accountTagPref.getTagId() == null) {
                continue;
            }
            accountTagPref.setAccountId(pathAccountId);
            accountTagPrefMapper.insert(accountTagPref);
        }
    }

    @Override
    public Account updateProfile(Long accountId, UpdateAccountProfileRequest request) {
        requireSelfOrAdmin(accountId);
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }

        Account existing = accountMapper.selectById(accountId);
        if (existing == null) {
            throw new ResourceNotFoundException("用户不存在, accountId=" + accountId);
        }

        Account update = new Account();
        update.setId(accountId);
        update.setUsername(trimToNull(request.getUsername()));
        update.setRegionCode(trimToNull(request.getRegionCode()));
        update.setAvatarUrl(trimToNull(request.getAvatarUrl()));
        update.setStatus(request.getStatus());
        update.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(update);
        return accountMapper.selectById(accountId);
    }

    @Override
    public Account updateRole(Long accountId, String role) {
        requireSelfOrAdmin(accountId);

        String normalizedRole = normalizeRole(role);
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        String currentRole = SecurityContextUtil.currentRole();
        boolean isAdmin = "ADMIN".equalsIgnoreCase(currentRole);
        if (!isAdmin && "ADMIN".equals(normalizedRole)) {
            throw new ForbiddenException("普通用户不能设置ADMIN角色");
        }
        if (!isAdmin && !USER_EDITABLE_ROLES.contains(normalizedRole)) {
            throw new IllegalArgumentException("角色仅支持USER/LEADER/BOTH");
        }

        Account existing = accountMapper.selectById(accountId);
        if (existing == null) {
            throw new ResourceNotFoundException("用户不存在, accountId=" + accountId);
        }
        if (!isAdmin && !accountId.equals(currentAccountId)) {
            throw new ForbiddenException("无权修改他人角色");
        }

        Account update = new Account();
        update.setId(accountId);
        update.setRole(normalizedRole);
        update.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(update);
        ensureLeaderProfileIfNeeded(accountId, normalizedRole);
        return accountMapper.selectById(accountId);
    }

    @Override
    public Account updateUserIntro(Long accountId, String intro) {
        requireSelfOrAdmin(accountId);

        Account existing = accountMapper.selectById(accountId);
        if (existing == null) {
            throw new ResourceNotFoundException("用户不存在, accountId=" + accountId);
        }
        if (!hasUserPermission(existing.getRole())) {
            throw new ForbiddenException("普通用户简介仅支持USER/BOTH角色，领队简介请使用/accounts/{id}/intro");
        }

        Account update = new Account();
        update.setId(accountId);
        update.setIntro(normalizeIntro(intro));
        update.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(update);
        return accountMapper.selectById(accountId);
    }

    @Override
    public void updatePassword(Long accountId, UpdatePasswordRequest request) {
        requireSelfOrAdmin(accountId);
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.getNewPassword() == null || request.getNewPassword().isBlank()) {
            throw new IllegalArgumentException("新密码不能为空");
        }
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("两次密码不一致");
        }

        Account existing = accountMapper.selectById(accountId);
        if (existing == null) {
            throw new ResourceNotFoundException("用户不存在, accountId=" + accountId);
        }

        Long currentAccountId = SecurityContextUtil.currentAccountId();
        String currentRole = SecurityContextUtil.currentRole();
        boolean isAdmin = "ADMIN".equalsIgnoreCase(currentRole);
        if (!isAdmin || accountId.equals(currentAccountId)) {
            if (request.getOldPassword() == null || request.getOldPassword().isBlank()) {
                throw new IllegalArgumentException("旧密码不能为空");
            }
            if (!passwordEncoder.matches(request.getOldPassword(), existing.getPasswordHash())) {
                throw new ForbiddenException("旧密码错误");
            }
        }

        Account update = new Account();
        update.setId(accountId);
        update.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        update.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(update);
    }

    @Override
    public void changeIntro(Long accountId, String intro) {
        requireSelfOrAdmin(accountId);

        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new ResourceNotFoundException("用户不存在, accountId=" + accountId);
        }

        if (!hasLeaderPermission(account.getRole())) {
            throw new ForbiddenException("只有领队可修改简介, 当前角色=" + account.getRole());
        }

        leaderProfileMapper.updateById(new LeaderProfile(accountId, intro, null, null));
        log.info("Leader intro updated, accountId={}", accountId);
    }

    @Override
    public Account getById(Long id) {
        return accountMapper.selectById(id);
    }

    @Override
    public List<AccountTagPref> getTagPrefs(Long id) {
        return accountTagPrefMapper.selectByAccountId(id);
    }

    @Override
    public LeaderProfile getLeaderProfile(Long id) {
        return leaderProfileMapper.selectById(id);
    }

    @Override
    public void changeAvatar(Account account) {
        accountMapper.updateById(account);
    }

    private void requireSelfOrAdmin(Long pathAccountId) {
        Long currentAccountId = SecurityContextUtil.currentAccountId();
        String currentRole = SecurityContextUtil.currentRole();

        if (currentAccountId == null) {
            throw new UnauthorizedException("未认证用户");
        }

        boolean isAdmin = "ADMIN".equalsIgnoreCase(currentRole);
        if (!isAdmin && !currentAccountId.equals(pathAccountId)) {
            throw new ForbiddenException("无权操作他人资源");
        }
    }

    private boolean hasLeaderPermission(String role) {
        return "LEADER".equalsIgnoreCase(role) || "BOTH".equalsIgnoreCase(role);
    }

    private boolean hasUserPermission(String role) {
        return "USER".equalsIgnoreCase(role) || "BOTH".equalsIgnoreCase(role);
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("角色不能为空");
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (!ALL_ROLES.contains(normalized)) {
            throw new IllegalArgumentException("角色仅支持USER/LEADER/BOTH/ADMIN");
        }
        return normalized;
    }

    private void ensureLeaderProfileIfNeeded(Long accountId, String role) {
        if (!hasLeaderPermission(role)) {
            return;
        }
        if (leaderProfileMapper.selectById(accountId) == null) {
            leaderProfileMapper.insert(new LeaderProfile(accountId, null, null, null));
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeIntro(String intro) {
        if (intro == null) {
            return null;
        }
        String trimmed = intro.trim();
        if (trimmed.length() > 500) {
            throw new IllegalArgumentException("简介不能超过500个字符");
        }
        return trimmed;
    }
}
