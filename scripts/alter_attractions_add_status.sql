SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attractions' AND COLUMN_NAME = 'status'
    ),
    'SELECT ''status exists''',
    'ALTER TABLE attractions ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT ''ACTIVE'' AFTER tel'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE attractions
SET status = 'ACTIVE'
WHERE status IS NULL OR status = '';
