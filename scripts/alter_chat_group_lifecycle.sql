-- 在“一项目一群”基础上补齐显式群成员、创建/加入/软删除能力。
-- 前置脚本：alter_chat_sessions_to_project_groups.sql

START TRANSACTION;

-- OPEN/MATCHING 项目尚未匹配领队，因此群会话必须允许 leader_account_id 为空。
ALTER TABLE chat_sessions
    MODIFY COLUMN leader_account_id BIGINT NULL;

CREATE TABLE IF NOT EXISTS chat_group_members (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    member_role ENUM('PUBLISHER', 'LEADER', 'PARTICIPANT') NOT NULL,
    member_status ENUM('JOINED', 'LEFT', 'KICKED') NOT NULL DEFAULT 'JOINED',
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_group_member (group_id, account_id),
    KEY idx_chat_group_member_account (account_id, member_status),
    CONSTRAINT fk_chat_group_member_group
        FOREIGN KEY (group_id) REFERENCES chat_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_group_member_account
        FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 为所有未结束项目补齐群；user_account_id 兼容保留为项目发布者。
INSERT INTO chat_sessions
    (project_id, user_account_id, leader_account_id, status)
SELECT p.id, p.owner_account_id, p.leader_account_id, 'ACTIVE'
FROM projects p
LEFT JOIN chat_sessions cs ON cs.project_id = p.id
WHERE cs.id IS NULL
  AND p.status IN ('OPEN', 'MATCHING', 'CONFIRMED', 'IN_PROGRESS');

UPDATE chat_sessions cs
INNER JOIN projects p ON p.id = cs.project_id
SET cs.user_account_id = p.owner_account_id,
    cs.leader_account_id = p.leader_account_id;

-- 先回填普通参团成员，不覆盖已经存在的特殊身份。
INSERT INTO chat_group_members
    (group_id, account_id, member_role, member_status)
SELECT cs.id, pm.account_id, 'PARTICIPANT', 'JOINED'
FROM chat_sessions cs
INNER JOIN project_members pm ON pm.project_id = cs.project_id
WHERE pm.join_status IN ('JOINED', 'COMPLETED')
ON DUPLICATE KEY UPDATE
    member_status = 'JOINED';

-- 再回填领队；发布者兼任领队时保留发布者身份。
INSERT INTO chat_group_members
    (group_id, account_id, member_role, member_status)
SELECT cs.id, p.leader_account_id, 'LEADER', 'JOINED'
FROM chat_sessions cs
INNER JOIN projects p ON p.id = cs.project_id
WHERE p.leader_account_id IS NOT NULL
ON DUPLICATE KEY UPDATE
    member_role = CASE
        WHEN chat_group_members.member_role = 'PUBLISHER' THEN 'PUBLISHER'
        ELSE 'LEADER'
    END,
    member_status = 'JOINED';

-- 最后回填发布者，确保身份优先级最高。
INSERT INTO chat_group_members
    (group_id, account_id, member_role, member_status)
SELECT cs.id, p.owner_account_id, 'PUBLISHER', 'JOINED'
FROM chat_sessions cs
INNER JOIN projects p ON p.id = cs.project_id
ON DUPLICATE KEY UPDATE
    member_role = 'PUBLISHER',
    member_status = 'JOINED';

COMMIT;

SELECT COUNT(*) AS chat_group_count FROM chat_sessions;
SELECT member_role, COUNT(*) AS member_count
FROM chat_group_members
WHERE member_status = 'JOINED'
GROUP BY member_role;
