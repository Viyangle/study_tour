-- 景点讲解自动同步：保存来源快照与同步元数据，并允许人工内容退出自动更新
ALTER TABLE attraction_guides
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL'
        COMMENT 'MANUAL/BAIDU_BAIKE/WIKIPEDIA/PARENT/AMAP' AFTER source_url,
    ADD COLUMN source_text TEXT NULL
        COMMENT '用于 AI 加工的来源资料快照' AFTER source_type,
    ADD COLUMN source_revision VARCHAR(64) NULL
        COMMENT '来源版本，如维基条目修订号' AFTER source_text,
    ADD COLUMN source_license VARCHAR(128) NULL
        COMMENT '文字资料许可证；图片许可证需另行核验' AFTER source_revision,
    ADD COLUMN source_attribution VARCHAR(500) NULL
        COMMENT '作者/编者及改写署名说明' AFTER source_license,
    ADD COLUMN source_hash CHAR(64) NULL
        COMMENT '景点资料、来源文本和生成模式哈希，用于跳过未变化内容' AFTER source_attribution,
    ADD COLUMN sync_status VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
        COMMENT 'MANUAL/SOURCED/PARENT/FALLBACK' AFTER source_hash,
    ADD COLUMN sync_error VARCHAR(500) NULL
        COMMENT '资料或 AI 加工降级原因，不在用户查询接口暴露' AFTER sync_status,
    ADD COLUMN auto_update TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否允许自动同步覆盖；人工维护时可设为 0' AFTER sync_error,
    ADD COLUMN synced_at DATETIME NULL AFTER auto_update,
    ADD INDEX idx_attraction_guides_auto_sync (auto_update, synced_at);
