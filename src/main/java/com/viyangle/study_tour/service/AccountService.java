package com.viyangle.study_tour.service;

import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.LeaderProfile;
import com.viyangle.study_tour.pojo.LoginRequest;
import com.viyangle.study_tour.pojo.RegisterRequest;
import com.viyangle.study_tour.pojo.UpdateAccountProfileRequest;
import com.viyangle.study_tour.pojo.UpdatePasswordRequest;

import java.util.List;

/**
 * AccountService 接口
 * 
 * 作用：定义账号相关的业务逻辑方法
 * 这些方法会被 Controller 调用，然后 Service 内部调用 Mapper 来操作数据库
 * 
 * 为什么要有 Service 层：Controller 只负责接收请求和返回响应，
 * 真正的业务逻辑（比如"注册时要检查手机号是否重复"）应该放在 Service 层
 */
public interface AccountService {
    /**
     * 登录
     * @param loginRequest 登录请求（包含手机号和密码）
     * @return 账号对象，如果登录失败返回null
     */
    Account login(LoginRequest loginRequest);

    /**
     * 注册
     * @param registerRequest 注册请求（包含用户名、手机号、密码、确认密码、角色）
     * @return 注册成功返回账号id，失败返回-1（手机号已存在）或-2（其他错误）
     */
    Long register(RegisterRequest registerRequest);

    void changeTagPrefs(Long pathAccountId, List<AccountTagPref> accountTagPrefs);

    Account updateProfile(Long accountId, UpdateAccountProfileRequest request);

    Account updateRole(Long accountId, String role);

    Account updateUserIntro(Long accountId, String intro);

    void updatePassword(Long accountId, UpdatePasswordRequest request);

    void changeIntro(Long accountId, String intro);

    Account getById(Long id);

    List<AccountTagPref> getTagPrefs(Long id);

    LeaderProfile getLeaderProfile(Long id);

    void changeAvatar(Account account);
}
