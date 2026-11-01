-- project_abandon_records 只写在 V1 基线里。
-- 存量库首次启动会被 baseline-on-migrate 直接标记到 V1（V1 不执行），因此拿不到这张表，
-- 结果是 POST /projects/{id}/abandon 在写审计记录时抛 SQL 异常、事务整体回滚并返回 500。
-- 这里用幂等增量补齐：先建过表的库（手工跑过 scripts/create_project_abandon_records.sql）
-- 与本脚本 no-op 兼容；空库由 V1 建表，本脚本同样 no-op。
CREATE TABLE IF NOT EXISTS project_abandon_records (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    project_id        BIGINT       NOT NULL COMMENT '被放弃的项目ID',
    leader_account_id BIGINT       NOT NULL COMMENT '放弃带队的领队账号ID',
    reason            VARCHAR(200) NOT NULL COMMENT '放弃原因(去首尾空格后5-200字)',
    previous_status   VARCHAR(20)  NOT NULL COMMENT '放弃前的项目状态',
    departure_at      DATETIME     DEFAULT NULL COMMENT '放弃时项目的出发时间快照',
    abandoned_at      DATETIME     NOT NULL COMMENT '放弃操作时间(服务器时间)',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    PRIMARY KEY (id),
    INDEX idx_abandon_records_project (project_id, abandoned_at),
    INDEX idx_abandon_records_leader (leader_account_id, abandoned_at),
    CONSTRAINT fk_abandon_records_project FOREIGN KEY (project_id)
        REFERENCES projects (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_abandon_records_leader FOREIGN KEY (leader_account_id)
        REFERENCES accounts (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='领队放弃带队审计记录';
