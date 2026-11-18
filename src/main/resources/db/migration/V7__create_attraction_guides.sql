-- 景点研学讲解：内容离线整理/生成并审核后入库，查询链路不调用 AI
CREATE TABLE IF NOT EXISTS attraction_guides (
    poi_id        VARCHAR(32)   NOT NULL COMMENT '景点 POI ID，与 attractions 一一对应',
    summary       VARCHAR(1000) NOT NULL COMMENT '景点简短介绍',
    content       TEXT          NULL COMMENT '详细讲解',
    study_points  JSON          NULL COMMENT '研学知识点，JSON 字符串数组',
    questions     JSON          NULL COMMENT '研学思考题，JSON 字符串数组',
    visit_tips    TEXT          NULL COMMENT '参观建议与注意事项',
    cover_url     VARCHAR(512)  NULL COMMENT '封面图片 URL',
    audio_url     VARCHAR(512)  NULL COMMENT '语音讲解 URL',
    source_name   VARCHAR(128)  NULL COMMENT '资料来源名称，如景点官网、博物馆官网',
    source_url    VARCHAR(512)  NULL COMMENT '资料来源链接',
    status        VARCHAR(20)   NOT NULL DEFAULT 'PUBLISHED' COMMENT 'DRAFT/PUBLISHED',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (poi_id),
    KEY idx_attraction_guides_status (status),
    CONSTRAINT fk_attraction_guides_poi_id
        FOREIGN KEY (poi_id) REFERENCES attractions (poi_id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='景点研学讲解内容表';
