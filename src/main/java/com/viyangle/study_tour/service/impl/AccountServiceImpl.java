package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.LeaderProfileMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.LeaderProfile;
import com.viyangle.study_tour.service.AccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AccountServiceImpl implements AccountService {

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private AccountTagPrefMapper accountTagPrefMapper;

    @Autowired
    private LeaderProfileMapper leaderProfileMapper;
    @Override
    public Account login(Account account) {
        return accountMapper.selectByPhoneAndPassword(account.getPhone(), account.getPasswordHash());
    }

    @Override
    public Long register(Account account) {
        account.setStatus(1);
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());
        if (accountMapper.selectByUsername()) {
            return (long) -1;
        }
        if (accountMapper.selectByPhone()) {
            return (long) -2;
        }
        accountMapper.insert(account);
        if (account.getRole().equals("LEADER")){
            leaderProfileMapper.insert(new LeaderProfile(account.getId(), null, null, null));
        }
        return account.getId();
    }

    @Override
    public void changeTagPrefs(List<AccountTagPref> accountTagPrefs) {
        accountTagPrefMapper.deleteByAccountId(accountTagPrefs.get(0).getAccountId());
        for (AccountTagPref accountTagPref : accountTagPrefs) {
            accountTagPrefMapper.insert(accountTagPref);
        }
    }

    @Override
    public void changeIntro(Long accountId, String intro) {
        if (accountMapper.selectById(accountId).getRole().equals("LEADER")) {
            leaderProfileMapper.updateById(new LeaderProfile(accountId, intro, null, null));
        }
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
}
