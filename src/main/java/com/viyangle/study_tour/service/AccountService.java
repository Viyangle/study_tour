package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.AccountTagPref;

import java.util.List;

public interface AccountService {
    Account login(Account account);

    Long register(Account account);

    void changeTagPrefs(List<AccountTagPref> accountTagPrefs);

    void changeIntro(Long accountId, String intro);
}
