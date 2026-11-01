-- 路线标签向量：一条路线可以同时属于多个主题
CREATE TABLE IF NOT EXISTS route_tag_weight (
    route_id      BIGINT       NOT NULL COMMENT 'routes.id',
    tag_id        BIGINT       NOT NULL COMMENT 'tags.id，只存 15 个 INTEREST 维度',
    weight        DECIMAL(4,3) NOT NULL DEFAULT 0.000 COMMENT '路线向量值 0~1',
    source        VARCHAR(16)  NOT NULL DEFAULT 'DERIVED' COMMENT 'DERIVED / MANUAL',
    vector_version INT         NOT NULL DEFAULT 1,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (route_id, tag_id),
    KEY idx_route_tag_weight_tag (tag_id, weight),
    CONSTRAINT fk_route_tag_weight_route FOREIGN KEY (route_id) REFERENCES routes (id) ON DELETE CASCADE,
    CONSTRAINT fk_route_tag_weight_tag FOREIGN KEY (tag_id) REFERENCES tags (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路线-标签向量（多标签）';
