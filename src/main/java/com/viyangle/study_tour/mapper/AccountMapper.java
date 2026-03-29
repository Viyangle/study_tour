package com.viyangle.study_tour.mapper;

import com.viyangle.study_tour.pojo.Account;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AccountMapper 接口
 * 作用：定义对 accounts 表的所有数据库操作方法
 * 这些方法的具体SQL实现写在 AccountMapper.xml 文件中
 * MyBatis 会自动根据 @Mapper 注解和 XML 文件生成实现类
 */
@Mapper
public interface AccountMapper {

    /**
     * 插入一条账号记录
     * @param account 账号对象
     * @return 影响的行数（通常是1）
     */
    int insert(Account account);

    /**
     * 根据id删除账号
     * @param id 账号id
     * @return 影响的行数
     */
    int deleteById(@Param("id") Long id);

    /**
     * 根据id更新账号信息
     * @param account 账号对象（必须包含id）
     * @return 影响的行数
     */
    int updateById(Account account);

    /**
     * 根据id查询账号
     * @param id 账号id
     * @return 账号对象，如果不存在返回null
     */
    Account selectById(@Param("id") Long id);

    /**
     * 根据手机号和密码查询账号（用于登录）
     * @param phone 手机号
     * @param passwordHash 密码（暂时存明文，字段名还是password_hash）
     * @return 账号对象，如果不存在返回null
     */
    Account selectByPhoneAndPassword(@Param("phone") String phone, @Param("passwordHash") String passwordHash);

    /**
     * 查询所有账号（通常用于管理后台）
     * @return 账号列表
     */
    List<Account> selectAll();

    /**
     * 检查手机号是否已存在（用于注册时校验）
     * @param phone 手机号
     * @return true表示已存在，false表示不存在
     */
    boolean existsByPhone(@Param("phone") String phone);

    /**
     * 检查用户名是否已存在（用于注册时校验）
     * @param username 用户名
     * @return true表示已存在，false表示不存在
     */
    boolean existsByUsername(@Param("username") String username);

    Account selectByPhone(String phone);
}
