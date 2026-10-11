-- 每个参团账号记录实际代表人数，并扩展路线发布详情字段。

SET @represented_count_added = NOT EXISTS(
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'project_members'
      AND COLUMN_NAME = 'represented_count'
);

SET @sql = IF(
    @represented_count_added = 0,
    'SELECT ''project_members.represented_count exists''',
    'ALTER TABLE project_members ADD COLUMN represented_count INT NOT NULL DEFAULT 1 AFTER join_status'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE()
          AND TABLE_NAME = 'project_members'
          AND CONSTRAINT_NAME = 'chk_project_member_represented_count'
    ),
    'SELECT ''chk_project_member_represented_count exists''',
    'ALTER TABLE project_members ADD CONSTRAINT chk_project_member_represented_count CHECK (represented_count > 0)'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects' AND COLUMN_NAME = 'departure_time'
    ),
    'SELECT ''projects.departure_time exists''',
    'ALTER TABLE projects ADD COLUMN departure_time TIME NULL AFTER departure_date'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects' AND COLUMN_NAME = 'start_point_type'
    ),
    'SELECT ''projects.start_point_type exists''',
    'ALTER TABLE projects ADD COLUMN start_point_type VARCHAR(32) NULL AFTER departure_time'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects' AND COLUMN_NAME = 'start_point'
    ),
    'SELECT ''projects.start_point exists''',
    'ALTER TABLE projects ADD COLUMN start_point VARCHAR(255) NULL AFTER start_point_type'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects' AND COLUMN_NAME = 'leader_requirements'
    ),
    'SELECT ''projects.leader_requirements exists''',
    'ALTER TABLE projects ADD COLUMN leader_requirements TEXT NULL AFTER start_point'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects' AND COLUMN_NAME = 'participant_requirements'
    ),
    'SELECT ''projects.participant_requirements exists''',
    'ALTER TABLE projects ADD COLUMN participant_requirements TEXT NULL AFTER leader_requirements'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 新发布页没有强制填写订单总人数上限；NULL 表示不限额。
ALTER TABLE projects MODIFY COLUMN max_members INT NULL DEFAULT NULL;

-- 尽可能保留历史 current_members：多出账号数的部分归到订单创建者代表人数。
DROP TEMPORARY TABLE IF EXISTS project_member_account_counts;
CREATE TEMPORARY TABLE project_member_account_counts AS
SELECT project_id, COUNT(*) AS account_count
FROM project_members
WHERE join_status IN ('JOINED', 'COMPLETED')
GROUP BY project_id;

UPDATE project_members pm
INNER JOIN projects p ON p.id = pm.project_id
INNER JOIN project_member_account_counts stats ON stats.project_id = pm.project_id
SET pm.represented_count = CASE
        WHEN pm.account_id = p.owner_account_id
             AND COALESCE(p.current_members, 0) > stats.account_count
            THEN p.current_members - stats.account_count + 1
        ELSE 1
    END
WHERE @represented_count_added = 1
  AND pm.join_status IN ('JOINED', 'COMPLETED');

DROP TEMPORARY TABLE project_member_account_counts;

UPDATE projects p
LEFT JOIN (
    SELECT project_id, SUM(represented_count) AS represented_total
    FROM project_members
    WHERE join_status IN ('JOINED', 'COMPLETED')
    GROUP BY project_id
) totals ON totals.project_id = p.id
SET p.current_members = COALESCE(totals.represented_total, 0);
