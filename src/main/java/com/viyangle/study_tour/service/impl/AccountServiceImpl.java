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

@Slf4j
@Service
public class AccountServiceImpl implements AccountService {

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
}
