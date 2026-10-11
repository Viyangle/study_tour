CREATE TABLE IF NOT EXISTS `project_abandon_records` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `project_id` bigint NOT NULL COMMENT '被放弃的项目ID',
  `leader_account_id` bigint NOT NULL COMMENT '放弃带队的领队账号ID',
  `reason` varchar(200) NOT NULL COMMENT '放弃原因（去除首尾空格后5-200字）',
  `previous_status` varchar(20) NOT NULL COMMENT '放弃前的项目状态',
  `departure_at` datetime DEFAULT NULL COMMENT '放弃时项目的出发时间快照',
  `abandoned_at` datetime NOT NULL COMMENT '放弃操作时间（服务器时间）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
  PRIMARY KEY (`id`),
  KEY `idx_abandon_records_project` (`project_id`, `abandoned_at`),
  KEY `idx_abandon_records_leader` (`leader_account_id`, `abandoned_at`),
  CONSTRAINT `fk_abandon_records_project`
    FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_abandon_records_leader`
    FOREIGN KEY (`leader_account_id`) REFERENCES `accounts` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='领队放弃带队审计记录';
