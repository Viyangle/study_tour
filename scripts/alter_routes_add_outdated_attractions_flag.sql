SET @sql = IF(
    EXISTS(
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'routes'
          AND COLUMN_NAME = 'contains_outdated_attractions'
    ),
    'SELECT ''contains_outdated_attractions exists''',
    'ALTER TABLE routes ADD COLUMN contains_outdated_attractions TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''Whether the route contains stale attractions'' AFTER tag'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE routes r
SET contains_outdated_attractions = EXISTS (
    SELECT 1
    FROM route_attractions ra
    JOIN attractions a ON a.poi_id = ra.poi_id
    WHERE ra.route_id = r.id
      AND COALESCE(a.status, 'ACTIVE') != 'ACTIVE'
);
