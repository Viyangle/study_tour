package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.LeaderProfile;

import java.util.List;

public interface AccountService {
    Account login(Account account);

    Long register(Account account);

    void changeTagPrefs(List<AccountTagPref> accountTagPrefs);

    void changeIntro(Long accountId, String intro);

    Account getById(Long id);

    List<AccountTagPref> getTagPrefs(Long id);

    LeaderProfile getLeaderProfile(Long id);

    void changeAvatar(Account account);
}
