-- 景点-标签关联表（知识图谱中"景点→标签"的边）
CREATE TABLE IF NOT EXISTS attraction_tag (
    poi_id     VARCHAR(32)  NOT NULL COMMENT '景点POI ID',
    tag_id     BIGINT       NOT NULL COMMENT '标签ID，对应 tags 表',
    source     VARCHAR(16)  NOT NULL DEFAULT 'LLM' COMMENT '来源: LLM / MANUAL',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (poi_id, tag_id),
    INDEX idx_attraction_tag_tag_id (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='景点-研学标签关联';

-- 景点相邻关系表（知识图谱中"景点→景点"的相邻边）
CREATE TABLE IF NOT EXISTS attraction_adjacency (
    from_poi_id    VARCHAR(32) NOT NULL COMMENT '起点景点POI ID',
    to_poi_id      VARCHAR(32) NOT NULL COMMENT '终点景点POI ID',
    transit_minutes INT        COMMENT '公交通勤时间(分钟)，NULL表示无公交',
    distance_m     INT         COMMENT '交通距离(米)',
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (from_poi_id, to_poi_id),
    INDEX idx_adjacency_to (to_poi_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='景点相邻关系(公交30分钟内)';
