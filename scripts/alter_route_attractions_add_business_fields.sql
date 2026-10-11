SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'route_attractions' AND COLUMN_NAME = 'opentime_today'
    ),
    'SELECT ''opentime_today exists''',
    'ALTER TABLE route_attractions ADD COLUMN opentime_today VARCHAR(128) NULL AFTER distance'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'route_attractions' AND COLUMN_NAME = 'opentime_week'
    ),
    'SELECT ''opentime_week exists''',
    'ALTER TABLE route_attractions ADD COLUMN opentime_week VARCHAR(512) NULL AFTER opentime_today'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'route_attractions' AND COLUMN_NAME = 'tel'
    ),
    'SELECT ''tel exists''',
    'ALTER TABLE route_attractions ADD COLUMN tel VARCHAR(128) NULL AFTER opentime_week'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE route_attractions ra
JOIN attractions a ON a.poi_id COLLATE utf8mb4_0900_ai_ci = ra.poi_id COLLATE utf8mb4_0900_ai_ci
SET ra.opentime_today = a.opentime_today,
    ra.opentime_week = a.opentime_week,
    ra.tel = a.tel;
