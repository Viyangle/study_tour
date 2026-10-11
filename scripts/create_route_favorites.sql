CREATE TABLE IF NOT EXISTS `route_favorites` (
  `account_id` bigint NOT NULL COMMENT '收藏账号ID',
  `route_id` bigint NOT NULL COMMENT '被收藏的路线ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
  PRIMARY KEY (`account_id`, `route_id`),
  KEY `idx_route_favorites_route_id` (`route_id`),
  KEY `idx_route_favorites_account_created` (`account_id`, `created_at`),
  CONSTRAINT `fk_route_favorites_account`
    FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_route_favorites_route`
    FOREIGN KEY (`route_id`) REFERENCES `routes` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='用户收藏路线关联记录';
