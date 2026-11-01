-- 景点标签向量化：15 维 INTEREST 维度的权重、证据与快照
-- 设计说明见 docs/attraction-tag-vector-design.md

-- 1) 维度定义
CREATE TABLE IF NOT EXISTS tag_dimension (
    dim_version  INT          NOT NULL COMMENT '维度版本，维度集合变化时递增',
    ordinal      TINYINT      NOT NULL COMMENT '1..15，向量下标',
    tag_id       BIGINT       NOT NULL COMMENT 'tags.id',
    code         VARCHAR(48)  NOT NULL COMMENT '前后端契约用的稳定编码',
    description  VARCHAR(200) DEFAULT NULL,
    enabled      TINYINT(1)   NOT NULL DEFAULT 1,
    PRIMARY KEY (dim_version, ordinal),
    UNIQUE KEY uk_dim_code (dim_version, code),
    CONSTRAINT fk_dim_tag FOREIGN KEY (tag_id) REFERENCES tags (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签向量维度定义';

-- 1.1) 空库自包含：补 15 个 INTEREST 标签（存量库已存在时 IGNORE 跳过）
INSERT IGNORE INTO tags (name, type) VALUES
('历史人文','INTEREST'), ('博物馆研学','INTEREST'), ('非遗体验','INTEREST'),
('科技探索','INTEREST'), ('自然生态','INTEREST'), ('地理地质','INTEREST'),
('航天航空','INTEREST'), ('农耕劳动','INTEREST'), ('艺术美育','INTEREST'),
('红色教育','INTEREST'), ('高校参访','INTEREST'), ('职业启蒙','INTEREST'),
('英语实践','INTEREST'), ('摄影记录','INTEREST'), ('亲子互动','INTEREST');

INSERT INTO tag_dimension (dim_version, ordinal, tag_id, code)
SELECT 1, d.ordinal, t.id, d.code
FROM (
    SELECT 1 AS ordinal, 'HISTORY_HUMANITIES' AS code, '历史人文' AS name
    UNION ALL SELECT 2,  'MUSEUM_STUDY',          '博物馆研学'
    UNION ALL SELECT 3,  'INTANGIBLE_HERITAGE',   '非遗体验'
    UNION ALL SELECT 4,  'TECH_EXPLORATION',      '科技探索'
    UNION ALL SELECT 5,  'NATURE_ECOLOGY',        '自然生态'
    UNION ALL SELECT 6,  'GEO_GEOLOGY',           '地理地质'
    UNION ALL SELECT 7,  'AEROSPACE',             '航天航空'
    UNION ALL SELECT 8,  'FARMING_LABOR',         '农耕劳动'
    UNION ALL SELECT 9,  'ART_EDUCATION',         '艺术美育'
    UNION ALL SELECT 10, 'RED_EDUCATION',         '红色教育'
    UNION ALL SELECT 11, 'UNIVERSITY_VISIT',      '高校参访'
    UNION ALL SELECT 12, 'CAREER_ENLIGHTENMENT',  '职业启蒙'
    UNION ALL SELECT 13, 'ENGLISH_PRACTICE',      '英语实践'
    UNION ALL SELECT 14, 'PHOTOGRAPHY',           '摄影记录'
    UNION ALL SELECT 15, 'PARENT_CHILD',          '亲子互动'
) d
JOIN tags t ON t.name = d.name AND t.type = 'INTEREST';

-- 2) 带权边：先加普通列
ALTER TABLE attraction_tag
    ADD COLUMN prior_weight    DECIMAL(4,3)  NOT NULL DEFAULT 1.000 COMMENT '目录先验 p，0..1',
    ADD COLUMN prior_strength  DECIMAL(5,2)  NOT NULL DEFAULT 3.00  COMMENT '先验伪计数 κ，越大越难被反馈推翻',
    ADD COLUMN pos_count       DECIMAL(10,3) NOT NULL DEFAULT 0.000 COMMENT '衰减后的正向证据',
    ADD COLUMN neg_count       DECIMAL(10,3) NOT NULL DEFAULT 0.000 COMMENT '衰减后的负向证据',
    ADD COLUMN last_decay_at   DATETIME      DEFAULT NULL,
    ADD COLUMN vector_version  INT           NOT NULL DEFAULT 1,
    ADD COLUMN updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

-- 2.1) 生成列与索引：先验与热度的加权平均，天然落在 0~1
ALTER TABLE attraction_tag
    ADD COLUMN effective_weight DECIMAL(4,3) AS (
        CAST(LEAST(1.000, GREATEST(0.000,
            (prior_strength * prior_weight + pos_count)
            / (prior_strength + pos_count + neg_count)
        )) AS DECIMAL(4,3))
    ) STORED COMMENT '生成列：后验权重（0~1），用于 SQL 软筛',
    ADD INDEX idx_attraction_tag_weight (tag_id, effective_weight);

-- 3) 反馈事件（append-only）
CREATE TABLE IF NOT EXISTS tag_feedback_event (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    account_id       BIGINT       DEFAULT NULL COMMENT 'NULL=匿名/系统',
    session_key      VARCHAR(64)  DEFAULT NULL COMMENT '前端会话，用于归因与去重',
    event_type       VARCHAR(32)  NOT NULL,
    poi_id           VARCHAR(32)  DEFAULT NULL,
    route_id         BIGINT       DEFAULT NULL,
    project_id       BIGINT       DEFAULT NULL,
    tag_id           BIGINT       DEFAULT NULL COMMENT 'TAG_CORRECTION 指定的维度',
    event_value      DECIMAL(8,3) NOT NULL DEFAULT 1.000 COMMENT '带符号的信号强度',
    intent_vector    JSON         DEFAULT NULL COMMENT '事件发生时的意图向量（15 维）',
    context          JSON         DEFAULT NULL COMMENT 'scene/position/client/modelVersion 白名单',
    occurred_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    received_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    idempotency_key  VARCHAR(80)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPLIED/IGNORED/SUSPICIOUS',
    attempts         TINYINT      NOT NULL DEFAULT 0,
    applied_at       DATETIME     DEFAULT NULL,
    apply_note       VARCHAR(200) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_feedback_idem (idempotency_key),
    KEY idx_feedback_poi_time (poi_id, occurred_at),
    KEY idx_feedback_status (status, occurred_at),
    KEY idx_feedback_account (account_id, occurred_at),
    KEY idx_feedback_route (route_id),
    CONSTRAINT fk_feedback_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签向量反馈事件';

-- 4) 向量快照（重建/人工覆盖/回滚）
CREATE TABLE IF NOT EXISTS attraction_tag_vector_history (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    poi_id               VARCHAR(32) NOT NULL,
    dim_version          INT         NOT NULL,
    vector_version       INT         NOT NULL,
    weights              JSON        NOT NULL COMMENT '15 个有效值，按 ordinal',
    priors               JSON        NOT NULL COMMENT '15 个先验值',
    supports             JSON        DEFAULT NULL COMMENT '15 个证据量',
    trigger_source       VARCHAR(24) NOT NULL COMMENT 'REBUILD/FEEDBACK/MANUAL/ROLLBACK',
    operator_account_id  BIGINT      DEFAULT NULL,
    note                 VARCHAR(200) DEFAULT NULL,
    created_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_vec_history_poi (poi_id, id),
    KEY idx_vec_history_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='景点标签向量快照';
