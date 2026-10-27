-- accounts.role 枚举缺少 ADMIN，与 AccountServiceImpl 中允许设置的角色不一致。
-- 代码支持并把 ADMIN 用于权限判断，但枚举只允许 USER/LEADER/BOTH，导致设置管理员时写入失败。
-- 执行顺序：在 create_project_abandon_records.sql 之后；存量库升级必执行，新建库由 full_schema.sql 直接包含。
-- 可重复执行：MODIFY 到相同定义不会报错。

ALTER TABLE `accounts`
    MODIFY COLUMN `role` ENUM ('USER', 'LEADER', 'BOTH', 'ADMIN') NOT NULL DEFAULT 'USER';
