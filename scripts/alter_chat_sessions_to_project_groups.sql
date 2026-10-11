-- 将原“项目内一对一会话”升级为“每个项目唯一群聊”。
-- 群成员不单独维护：项目 JOINED 成员、项目发起人和已确认领队自动拥有访问权限。
-- DONE/CANCELLED 项目的群聊会被停用，历史消息保留。

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'chat_sessions'
          AND COLUMN_NAME = 'status'
    ),
    'SELECT ''chat_sessions.status exists''',
    'ALTER TABLE chat_sessions ADD COLUMN status ENUM(''ACTIVE'', ''DISABLED'') NOT NULL DEFAULT ''ACTIVE'' AFTER leader_account_id'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'chat_sessions'
          AND COLUMN_NAME = 'disabled_at'
    ),
    'SELECT ''chat_sessions.disabled_at exists''',
    'ALTER TABLE chat_sessions ADD COLUMN disabled_at DATETIME NULL AFTER status'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 先把同一项目旧会话的消息归到最早的会话，再删除重复会话。
DROP TEMPORARY TABLE IF EXISTS chat_session_canonical;
CREATE TEMPORARY TABLE chat_session_canonical AS
SELECT project_id, MIN(id) AS session_id
FROM chat_sessions
GROUP BY project_id;

UPDATE chat_messages cm
INNER JOIN chat_sessions old_cs ON old_cs.id = cm.session_id
INNER JOIN chat_session_canonical canonical ON canonical.project_id = old_cs.project_id
SET cm.session_id = canonical.session_id
WHERE cm.session_id != canonical.session_id;

DELETE duplicate_session
FROM chat_sessions duplicate_session
INNER JOIN chat_session_canonical canonical
        ON canonical.project_id = duplicate_session.project_id
WHERE duplicate_session.id != canonical.session_id;

-- 兼容保留旧列，但统一为项目发起人与领队；实际群成员资格由项目关系动态判断。
UPDATE chat_sessions cs
INNER JOIN projects p ON p.id = cs.project_id
SET cs.user_account_id = p.owner_account_id,
    cs.leader_account_id = p.leader_account_id
WHERE p.leader_account_id IS NOT NULL;

-- 为升级时已经确认领队、但还没有会话的进行中项目补建群聊。
INSERT INTO chat_sessions (project_id, user_account_id, leader_account_id, status)
SELECT p.id, p.owner_account_id, p.leader_account_id, 'ACTIVE'
FROM projects p
LEFT JOIN chat_sessions cs ON cs.project_id = p.id
WHERE cs.id IS NULL
  AND p.leader_account_id IS NOT NULL
  AND p.status IN ('CONFIRMED', 'IN_PROGRESS');

UPDATE chat_sessions cs
INNER JOIN projects p ON p.id = cs.project_id
SET cs.status = CASE
        WHEN p.status IN ('DONE', 'CANCELLED') THEN 'DISABLED'
        ELSE 'ACTIVE'
    END,
    cs.disabled_at = CASE
        WHEN p.status IN ('DONE', 'CANCELLED') THEN COALESCE(cs.disabled_at, CURRENT_TIMESTAMP)
        ELSE NULL
    END;

DROP TEMPORARY TABLE chat_session_canonical;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'chat_sessions'
          AND INDEX_NAME = 'uk_project_group_chat'
    ),
    'SELECT ''uk_project_group_chat exists''',
    'ALTER TABLE chat_sessions ADD UNIQUE KEY uk_project_group_chat (project_id)'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'chat_sessions'
          AND INDEX_NAME = 'uk_project_chat'
    ),
    'ALTER TABLE chat_sessions DROP INDEX uk_project_chat',
    'SELECT ''uk_project_chat does not exist'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
