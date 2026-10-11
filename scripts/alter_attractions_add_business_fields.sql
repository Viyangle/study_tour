SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attractions' AND COLUMN_NAME = 'opentime_today'
    ),
    'SELECT ''opentime_today exists''',
    'ALTER TABLE attractions ADD COLUMN opentime_today VARCHAR(128) NULL AFTER distance'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attractions' AND COLUMN_NAME = 'opentime_week'
    ),
    'SELECT ''opentime_week exists''',
    'ALTER TABLE attractions ADD COLUMN opentime_week VARCHAR(512) NULL AFTER opentime_today'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attractions' AND COLUMN_NAME = 'tel'
    ),
    'SELECT ''tel exists''',
    'ALTER TABLE attractions ADD COLUMN tel VARCHAR(128) NULL AFTER opentime_week'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
