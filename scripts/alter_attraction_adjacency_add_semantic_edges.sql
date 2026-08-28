-- 将已有的地理邻接表升级为同时支持地理边和语义边。
-- 现有记录会保留，并统一标记为 GEOGRAPHIC。
ALTER TABLE `attraction_adjacency`
    ADD COLUMN `relation_type` varchar(20) NOT NULL DEFAULT 'GEOGRAPHIC'
        COMMENT 'GEOGRAPHIC / THEMATIC' AFTER `distance_m`,
    ADD COLUMN `similarity_score` double DEFAULT NULL
        COMMENT '语义相似度(0-1)，仅 THEMATIC 有值' AFTER `relation_type`,
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (`from_poi_id`, `to_poi_id`, `relation_type`),
    ADD INDEX `idx_adjacency_type` (`relation_type`);
