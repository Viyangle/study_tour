-- accounts.role 枚举缺少 ADMIN，与 AccountServiceImpl 允许设置的角色不一致。
-- 代码把 ADMIN 用于权限判断，但枚举只允许 USER/LEADER/BOTH，设置管理员时会写入失败。
ALTER TABLE `accounts`
    MODIFY COLUMN `role` ENUM ('USER', 'LEADER', 'BOTH', 'ADMIN') NOT NULL DEFAULT 'USER';
