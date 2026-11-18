-- 反馈 2.0：把语义先验、全局学习层、曝光、排序模型和用户偏好拆开
-- 设计说明见 docs/tag-feedback-v2-design.md

-- 1) 全局学习层：每个 (poi, tag) 的后验证据，与 attraction_tag 的语义先验分离
CREATE TABLE IF NOT EXISTS attraction_tag_learned (
    poi_id          VARCHAR(32)   NOT NULL,
    tag_id          BIGINT        NOT NULL,
    pos_count       DECIMAL(10,3) NOT NULL DEFAULT 0.000 COMMENT '衰减后的正向选择证据',
    neg_count       DECIMAL(10,3) NOT NULL DEFAULT 0.000 COMMENT '衰减后的负向选择证据',
    distinct_users  INT           NOT NULL DEFAULT 0 COMMENT '近 30 天贡献过证据的不同用户数',
    last_decay_at   DATETIME      DEFAULT NULL,
    vector_version  INT           NOT NULL DEFAULT 1 COMMENT '学习层版本，用于 API 缓存失效',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (poi_id, tag_id),
    KEY idx_learned_tag (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签全局学习层（与先验分离）';

-- 2) 学习层的用户贡献记录：用于计算 distinct_users，而不是用事件数冒充用户数
CREATE TABLE IF NOT EXISTS attraction_tag_learned_account (
    poi_id       VARCHAR(32) NOT NULL,
    tag_id       BIGINT      NOT NULL,
    account_id   BIGINT      NOT NULL,
    last_seen_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (poi_id, tag_id, account_id),
    KEY idx_learned_account_seen (poi_id, tag_id, last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习层用户贡献记录';

-- 3) 曝光日志：区分「没选」和「没看到」，并记录位置偏差
CREATE TABLE IF NOT EXISTS tag_impression (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    account_id    BIGINT      DEFAULT NULL,
    session_key   VARCHAR(64) DEFAULT NULL,
    scene         VARCHAR(32) NOT NULL DEFAULT 'RECOMMEND',
    poi_id        VARCHAR(32) NOT NULL,
    position      INT         NOT NULL DEFAULT 0,
    model_version VARCHAR(32) DEFAULT NULL,
    shown_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_impression_session (session_key, shown_at),
    KEY idx_impression_poi (poi_id, shown_at),
    KEY idx_impression_account (account_id, shown_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐/路线候选曝光日志';

-- 4) 排序模型：pairwise 训练产物，JSON 存特征系数，避免一行一个特征
CREATE TABLE IF NOT EXISTS tag_ranking_model (
    model_version VARCHAR(32)   NOT NULL,
    coefficients  JSON          NOT NULL COMMENT '{feature: coefficient}',
    intercept     DECIMAL(10,6) NOT NULL DEFAULT 0.000000,
    sample_count  INT           NOT NULL DEFAULT 0,
    active        TINYINT(1)    NOT NULL DEFAULT 0,
    trained_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (model_version),
    KEY idx_ranking_model_active (active, trained_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='pairwise 排序模型';

-- 5) 用户长期偏好：只影响该用户的排序，不写回景点先验
CREATE TABLE IF NOT EXISTS account_tag_affinity (
    account_id BIGINT       NOT NULL,
    tag_id     BIGINT       NOT NULL,
    affinity   DECIMAL(4,3) NOT NULL DEFAULT 0.000,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户标签偏好（与景点先验分离）';

-- 6) 事件表补来源、维度版本、场景、位置，便于归因与防伪造
ALTER TABLE tag_feedback_event
    ADD COLUMN source       VARCHAR(16) NOT NULL DEFAULT 'SERVER' COMMENT 'CLIENT/SERVER' AFTER session_key,
    ADD COLUMN dim_version  INT         DEFAULT NULL COMMENT 'intent_vector 的维度版本' AFTER intent_vector,
    ADD COLUMN model_version VARCHAR(32) DEFAULT NULL COMMENT '产生该事件的排序模型版本' AFTER dim_version,
    ADD COLUMN scene        VARCHAR(32) DEFAULT NULL COMMENT 'RECOMMEND/AI_PLAN/PROJECT' AFTER model_version,
    ADD COLUMN position     INT         DEFAULT NULL COMMENT '曝光位置，从 1 开始' AFTER scene,
    ADD COLUMN exposure_id  BIGINT      DEFAULT NULL COMMENT '关联 tag_impression.id' AFTER position;

ALTER TABLE tag_feedback_event
    ADD INDEX idx_feedback_status_id (status, id),
    ADD INDEX idx_feedback_account_poi (account_id, poi_id, status, occurred_at);
