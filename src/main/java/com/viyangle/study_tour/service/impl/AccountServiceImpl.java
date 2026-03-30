package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.LeaderProfileMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.AccountTagPref;
import com.viyangle.study_tour.pojo.LeaderProfile;
import com.viyangle.study_tour.pojo.LoginRequest;
import com.viyangle.study_tour.pojo.RegisterRequest;
import com.viyangle.study_tour.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AccountServiceImpl 类
 * 作用：AccountService 接口的具体实现类
 * @Service 注解：告诉 Spring 这是一个服务类，Spring 会自动创建对象并管理它的生命周期
 * 这个类实现了账号相关的所有业务逻辑：
 * - 登录：根据手机号和密码查询账号
 * - 注册：检查手机号是否重复，创建账号，如果是领队还要创建领队资料
 */
@Slf4j
@Service
public class AccountServiceImpl implements AccountService {

    // @Autowired：告诉 Spring 自动注入（赋值）这个对象
    // Spring 会在容器中查找 AccountMapper 的实现类（MyBatis 自动生成的），然后赋值给这个变量
    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private AccountTagPrefMapper accountTagPrefMapper;

    @Autowired
    private LeaderProfileMapper leaderProfileMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    /**
     * 登录方法
     * 流程：
     * 1. 根据手机号查询账号
     * 2. 如果账号存在，使用BCrypt验证密码
     * 3. 如果验证成功返回账号对象，否则返回null
     */
    @Override
    public Account login(LoginRequest loginRequest) {
        Account account = accountMapper.selectByPhone(loginRequest.getPhone());
        if (account != null && passwordEncoder.matches(loginRequest.getPassword(), account.getPasswordHash())) {
            return account;
        }
        return null;
    }

    /**
     * 注册方法
     * 流程：
     * 1. 检查用户名是否已存在（如果存在返回 -2）
     * 2. 检查手机号是否已存在（如果存在返回 -1）
     * 3. 创建 Account 对象，设置所有字段
     * 4. 设置创建时间和更新时间（后端自动生成）
     * 5. 插入数据库
     * 6. 如果是领队（role == "LEADER"），还要在 leader_profiles 表插入一条记录
     * 7. 返回新创建的账号 id
     * 返回值：
     * - 成功：返回账号 id（Long 类型）
     * - 失败：返回 -1（手机号已存在）或 -2（用户名已存在）
     */
    @Override
    public Long register(RegisterRequest registerRequest) {
        // 1. 检查用户名是否已存在
        if (accountMapper.existsByUsername(registerRequest.getUsername())) {
            return -2L;
        }

        // 2. 检查手机号是否已存在
        if (accountMapper.existsByPhone(registerRequest.getPhone())) {
            return -1L;
        }

        // 3. 创建 Account 对象，把 RegisterRequest 的数据复制过来
        Account account = new Account();
        account.setRole(registerRequest.getRole());           // 角色：USER 或 LEADER
        account.setUsername(registerRequest.getUsername());   // 用户名（唯一，不可重复）
        account.setPhone(registerRequest.getPhone());         // 手机号（唯一）
        // 使用BCrypt加密密码
        account.setPasswordHash(passwordEncoder.encode(registerRequest.getPassword()));
        String regionCode = registerRequest.getRegionCode() == null ? null : registerRequest.getRegionCode().trim();
        account.setRegionCode(regionCode);
        account.setStatus(1);//1表示正常
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());

        // 4. 插入数据库
        accountMapper.insert(account);
        // insert 执行后，account.getId() 会被自动赋值（因为 XML 中设置了 useGeneratedKeys="true"）

        // 5. 如果是领队，还要在 leader_profiles 表创建一条记录
        if ("LEADER".equals(account.getRole())) {
            // 创建领队资料对象：accountId 是刚插入的账号 id，其他字段暂时为 null
            LeaderProfile leaderProfile = new LeaderProfile(
                account.getId(),  // accountId：关联到刚创建的账号
                null,            // intro：简介，暂时为空
                null,            // totalRating：总评分，暂时为 0
                null             // ratingCount：评分人数，暂时为 0
            );
            leaderProfileMapper.insert(leaderProfile);
        }

        // 6. 返回新创建的账号 id
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
        // 1. 检查用户是否存在
        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new RuntimeException("用户不存在，accountId=" + accountId);
        }
        
        // 2. 检查角色是否为 LEADER
        if (!"LEADER".equals(account.getRole())) {
            throw new RuntimeException("只有领队可以修改简介，当前用户角色=" + account.getRole());
        }
        
        // 3. 更新领队简介
        leaderProfileMapper.updateById(new LeaderProfile(accountId, intro, null, null));
        log.info("领队简介已更新：accountId={}, intro={}", accountId, intro);
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
