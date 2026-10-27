-- Flyway V1 baseline：由 scripts/full_schema.sql 提取结构而来（已去除演示数据）。
-- 存量库由 baseline-on-migrate 标记为已执行；空库会执行本脚本建表。
-- 与 full_schema.sql 的差异：删除 DROP TABLE、改为 CREATE TABLE IF NOT EXISTS，保证不会误删数据。

-- MySQL dump 10.13  Distrib 8.0.43, for Win64 (x86_64)
--
-- Host: localhost    Database: study_tour
-- ------------------------------------------------------
-- Server version	8.0.43

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `account_tag_prefs`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `account_tag_prefs` (
  `account_id` bigint NOT NULL,
  `tag_id` bigint NOT NULL,
  PRIMARY KEY (`account_id`,`tag_id`),
  KEY `tag_id` (`tag_id`),
  CONSTRAINT `account_tag_prefs_ibfk_1` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`),
  CONSTRAINT `account_tag_prefs_ibfk_2` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `account_tag_prefs`
--


--
-- Table structure for table `accounts`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `accounts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role` enum('USER','LEADER','BOTH','ADMIN') NOT NULL DEFAULT 'USER',
  `username` varchar(50) NOT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `password_hash` varchar(255) NOT NULL,
  `region_code` varchar(20) NOT NULL,
  `avatar_url` varchar(255) DEFAULT NULL,
  `intro` varchar(500) DEFAULT NULL COMMENT '普通用户简介',
  `status` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `accounts`
--


--
-- Table structure for table `attractions`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `attractions` (
  `poi_id` varchar(32) NOT NULL COMMENT 'POI唯一ID（样例里的id）',
  `parent_poi_id` varchar(32) DEFAULT NULL COMMENT '父POI ID（样例里的parent）',
  `name` varchar(128) NOT NULL COMMENT '景点名称',
  `address` varchar(255) DEFAULT NULL COMMENT '地址',
  `location` varchar(64) DEFAULT NULL COMMENT '经纬度，格式：lng,lat',
  `pcode` varchar(20) DEFAULT NULL COMMENT '省编码',
  `pname` varchar(64) DEFAULT NULL COMMENT '省名称',
  `citycode` varchar(20) DEFAULT NULL COMMENT '城市编码',
  `cityname` varchar(64) DEFAULT NULL COMMENT '城市名称',
  `adcode` varchar(20) DEFAULT NULL COMMENT '区县编码',
  `adname` varchar(64) DEFAULT NULL COMMENT '区县名称',
  `type` varchar(255) DEFAULT NULL COMMENT '分类名称',
  `typecode` varchar(128) DEFAULT NULL COMMENT '分类编码',
  `distance` varchar(32) DEFAULT NULL COMMENT '距离（原始字符串）',
  `opentime_today` varchar(128) DEFAULT NULL,
  `opentime_week` varchar(512) DEFAULT NULL,
  `tel` varchar(128) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`poi_id`),
  UNIQUE KEY `uk_attractions_poi_id` (`poi_id`),
  KEY `idx_attractions_adcode` (`adcode`),
  KEY `idx_attractions_citycode` (`citycode`),
  KEY `idx_attractions_typecode` (`typecode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='景点表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `attractions`
--


--
-- Table structure for table `chat_messages`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `chat_messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `sender_account_id` bigint NOT NULL,
  `content` text NOT NULL,
  `msg_type` enum('TEXT','IMAGE','SYSTEM') NOT NULL DEFAULT 'TEXT',
  `sent_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `sender_account_id` (`sender_account_id`),
  KEY `idx_msg_session_time` (`session_id`,`sent_at`),
  CONSTRAINT `chat_messages_ibfk_1` FOREIGN KEY (`session_id`) REFERENCES `chat_sessions` (`id`),
  CONSTRAINT `chat_messages_ibfk_2` FOREIGN KEY (`sender_account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_messages`
--


--
-- Table structure for table `chat_sessions`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `chat_sessions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `user_account_id` bigint NOT NULL,
  `leader_account_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_chat` (`project_id`,`user_account_id`,`leader_account_id`),
  KEY `user_account_id` (`user_account_id`),
  KEY `leader_account_id` (`leader_account_id`),
  CONSTRAINT `chat_sessions_ibfk_1` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `chat_sessions_ibfk_2` FOREIGN KEY (`user_account_id`) REFERENCES `accounts` (`id`),
  CONSTRAINT `chat_sessions_ibfk_3` FOREIGN KEY (`leader_account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_sessions`
--


--
-- Table structure for table `leader_applications`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `leader_applications` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `leader_account_id` bigint NOT NULL,
  `status` enum('PENDING','ACCEPTED','REJECTED','CANCELLED') NOT NULL DEFAULT 'PENDING',
  `message` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_leader` (`project_id`,`leader_account_id`),
  KEY `leader_account_id` (`leader_account_id`),
  CONSTRAINT `leader_applications_ibfk_1` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `leader_applications_ibfk_2` FOREIGN KEY (`leader_account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `leader_applications`
--


--
-- Table structure for table `leader_profiles`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `leader_profiles` (
  `account_id` bigint NOT NULL,
  `intro` varchar(500) DEFAULT NULL,
  `total_rating` int DEFAULT '0',
  `rating_count` int DEFAULT '0',
  PRIMARY KEY (`account_id`),
  CONSTRAINT `leader_profiles_ibfk_1` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `leader_profiles`
--


--
-- Table structure for table `project_members`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `project_members` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `account_id` bigint NOT NULL,
  `join_status` enum('JOINED','QUIT','KICKED','COMPLETED') NOT NULL DEFAULT 'JOINED',
  `joined_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_member` (`project_id`,`account_id`),
  KEY `account_id` (`account_id`),
  CONSTRAINT `project_members_ibfk_1` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `project_members_ibfk_2` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `project_members`
--


--
-- Table structure for table `projects`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `projects` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `route_id` bigint NOT NULL,
  `owner_account_id` bigint NOT NULL,
  `leader_account_id` bigint DEFAULT NULL,
  `title` varchar(100) NOT NULL,
  `departure_date` date DEFAULT NULL,
  `max_members` int NOT NULL DEFAULT '4',
  `current_members` int NOT NULL DEFAULT '1',
  `status` enum('OPEN','MATCHING','CONFIRMED','IN_PROGRESS','DONE','CANCELLED') NOT NULL DEFAULT 'OPEN',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `region_adcode` char(6) DEFAULT NULL COMMENT '关联 regions.adcode',
  `tag` enum('历史人文','博物馆研学','非遗体验','科技探索','自然生态','地理地质','航天航空','农耕劳动','艺术美育','红色教育','高校参访','职业启蒙','英语实践','摄影记录','亲子互动') DEFAULT NULL COMMENT '标签枚举',
  PRIMARY KEY (`id`),
  KEY `route_id` (`route_id`),
  KEY `owner_account_id` (`owner_account_id`),
  KEY `leader_account_id` (`leader_account_id`),
  KEY `idx_project_status` (`status`),
  KEY `idx_project_departure` (`departure_date`),
  KEY `idx_projects_region_adcode` (`region_adcode`),
  KEY `idx_projects_tag` (`tag`),
  CONSTRAINT `fk_projects_region_adcode` FOREIGN KEY (`region_adcode`) REFERENCES `region` (`adcode`) ON DELETE SET NULL ON UPDATE CASCADE,
  CONSTRAINT `projects_ibfk_1` FOREIGN KEY (`route_id`) REFERENCES `routes` (`id`),
  CONSTRAINT `projects_ibfk_2` FOREIGN KEY (`owner_account_id`) REFERENCES `accounts` (`id`),
  CONSTRAINT `projects_ibfk_3` FOREIGN KEY (`leader_account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `projects`
--


--
-- Table structure for table `reference_pairs`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `reference_pairs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tag` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `notes` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `region_adcode` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `from_poi_id` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `from_poi_name` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `to_poi_id` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `to_poi_name` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_reference_pairs_tag` (`tag`),
  KEY `idx_reference_pairs_region_adcode` (`region_adcode`),
  KEY `idx_reference_pairs_from_poi_id` (`from_poi_id`),
  KEY `idx_reference_pairs_to_poi_id` (`to_poi_id`),
  KEY `idx_reference_pairs_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `reference_pairs`
--


--
-- Table structure for table `region`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `region` (
  `adcode` char(6) NOT NULL,
  `name` varchar(64) NOT NULL,
  `level` tinyint NOT NULL,
  `parent_adcode` char(6) DEFAULT NULL,
  `citycode` varchar(10) DEFAULT NULL,
  `is_virtual` tinyint NOT NULL DEFAULT '0',
  `has_children` tinyint(1) DEFAULT '0',
  PRIMARY KEY (`adcode`),
  KEY `idx_parent` (`parent_adcode`),
  KEY `idx_level` (`level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `region`
--


--
-- Table structure for table `reviews`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `reviews` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `route_id` bigint NOT NULL,
  `from_account_id` bigint NOT NULL,
  `to_account_id` bigint DEFAULT NULL,
  `review_type` enum('USER_TO_LEADER','LEADER_TO_USER','ROUTE_ONLY') NOT NULL,
  `overall_score` tinyint NOT NULL,
  `content` varchar(500) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `project_id` (`project_id`),
  KEY `from_account_id` (`from_account_id`),
  KEY `idx_review_to` (`to_account_id`),
  KEY `idx_review_route` (`route_id`),
  CONSTRAINT `reviews_ibfk_1` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `reviews_ibfk_2` FOREIGN KEY (`route_id`) REFERENCES `routes` (`id`),
  CONSTRAINT `reviews_ibfk_3` FOREIGN KEY (`from_account_id`) REFERENCES `accounts` (`id`),
  CONSTRAINT `reviews_ibfk_4` FOREIGN KEY (`to_account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `reviews`
--


--
-- Table structure for table `route_attractions`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `route_attractions` (
  `route_id` bigint NOT NULL COMMENT 'Route ID',
  `visit_order` int NOT NULL COMMENT 'Order in route, starts from 1',
  `visit_time` datetime DEFAULT NULL COMMENT 'Planned visit datetime',
  `recommended_duration` int DEFAULT NULL COMMENT 'Recommended duration in minutes',
  `notes` varchar(500) DEFAULT NULL COMMENT 'Notes',
  `poi_id` varchar(32) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `parent_poi_id` varchar(32) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `name` varchar(128) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `address` varchar(255) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `location` varchar(64) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `pcode` varchar(20) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `pname` varchar(64) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `citycode` varchar(20) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `cityname` varchar(64) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `adcode` varchar(20) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `adname` varchar(64) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `type` varchar(255) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `typecode` varchar(128) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `distance` varchar(32) DEFAULT NULL COMMENT 'Denormalized from attractions',
  `opentime_today` varchar(128) DEFAULT NULL,
  `opentime_week` varchar(512) DEFAULT NULL,
  `tel` varchar(128) DEFAULT NULL,
  `attraction_created_at` datetime DEFAULT NULL COMMENT 'Denormalized from attractions.created_at',
  `attraction_updated_at` datetime DEFAULT NULL COMMENT 'Denormalized from attractions.updated_at',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Node created time',
  PRIMARY KEY (`route_id`,`visit_order`),
  KEY `idx_route_attractions_route_id` (`route_id`),
  KEY `idx_route_attractions_adcode` (`adcode`),
  KEY `fk_route_attractions_poi_id` (`poi_id`),
  CONSTRAINT `fk_route_attractions_poi_id` FOREIGN KEY (`poi_id`) REFERENCES `attractions` (`poi_id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_route_attractions_route_id` FOREIGN KEY (`route_id`) REFERENCES `routes` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Route nodes with denormalized attraction snapshot';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `route_attractions`
--


--
-- Table structure for table `routes`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `routes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `region_adcode` char(6) DEFAULT NULL COMMENT '关联 regions.adcode',
  `tag` enum('历史人文','博物馆研学','非遗体验','科技探索','自然生态','地理地质','航天航空','农耕劳动','艺术美育','红色教育','高校参访','职业启蒙','英语实践','摄影记录','亲子互动') DEFAULT NULL COMMENT '标签枚举',
  PRIMARY KEY (`id`),
  KEY `idx_routes_region_adcode` (`region_adcode`),
  KEY `idx_routes_tag` (`tag`),
  CONSTRAINT `fk_routes_region_adcode` FOREIGN KEY (`region_adcode`) REFERENCES `region` (`adcode`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `routes`
--


--
-- Table structure for table `route_favorites`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `route_favorites` (
  `account_id` bigint NOT NULL COMMENT '收藏账号ID',
  `route_id` bigint NOT NULL COMMENT '被收藏的路线ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
  PRIMARY KEY (`account_id`,`route_id`),
  KEY `idx_route_favorites_route_id` (`route_id`),
  KEY `idx_route_favorites_account_created` (`account_id`,`created_at`),
  CONSTRAINT `fk_route_favorites_account` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_route_favorites_route` FOREIGN KEY (`route_id`) REFERENCES `routes` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户收藏路线关联记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `tags`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE IF NOT EXISTS `tags` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `type` enum('INTEREST','ROUTE_STYLE','CROWD','SCENIC') NOT NULL DEFAULT 'INTEREST',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `tags`
--


--
-- Dumping events for database 'study_tour'
--

--
-- Dumping routines for database 'study_tour'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-07-20 15:11:30

-- ============================================================
-- 补齐代码需要但原始 dump 缺失的字段
-- ============================================================

-- attractions 表新增 status 字段
ALTER TABLE `attractions` ADD COLUMN `status` varchar(20) DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/INACTIVE' AFTER `tel`;
ALTER TABLE `attractions` ADD INDEX `idx_attractions_status` (`status`);

-- projects 表新增 departure_time, start_point_type, start_point, leader_requirements, participant_requirements
ALTER TABLE `projects` ADD COLUMN `departure_time` time DEFAULT NULL COMMENT '出发时间' AFTER `departure_date`;
ALTER TABLE `projects` ADD COLUMN `start_point_type` varchar(20) DEFAULT NULL COMMENT '出发点类型: CURRENT_LOCATION/MANUAL' AFTER `departure_time`;
ALTER TABLE `projects` ADD COLUMN `start_point` varchar(255) DEFAULT NULL COMMENT '出发点' AFTER `start_point_type`;
ALTER TABLE `projects` ADD COLUMN `leader_requirements` text DEFAULT NULL COMMENT '领队要求' AFTER `start_point`;
ALTER TABLE `projects` ADD COLUMN `participant_requirements` text DEFAULT NULL COMMENT '参与者要求' AFTER `leader_requirements`;

-- project_members 表新增 represented_count
ALTER TABLE `project_members` ADD COLUMN `represented_count` int DEFAULT 1 COMMENT '代表人数' AFTER `join_status`;

-- chat_sessions 表新增 status, disabled_at
ALTER TABLE `chat_sessions` ADD COLUMN `status` varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED' AFTER `leader_account_id`;
ALTER TABLE `chat_sessions` ADD COLUMN `disabled_at` datetime DEFAULT NULL COMMENT '停用时间' AFTER `status`;

-- ============================================================
-- 新增表：chat_group_members（群组生命周期）
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_group_members (
    session_id    BIGINT       NOT NULL COMMENT 'chat_sessions.id',
    account_id    BIGINT       NOT NULL COMMENT 'accounts.id',
    role          VARCHAR(20)  NOT NULL DEFAULT 'MEMBER' COMMENT 'USER / LEADER / SYSTEM',
    joined_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at       DATETIME     DEFAULT NULL,
    PRIMARY KEY (session_id, account_id),
    INDEX idx_cgm_account (account_id),
    CONSTRAINT fk_cgm_session FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_cgm_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天群组成员';

-- ============================================================
-- 景点-标签关联表（知识图谱中"景点→标签"的边）
-- ============================================================
CREATE TABLE IF NOT EXISTS attraction_tag (
    poi_id     VARCHAR(32)  NOT NULL COMMENT '景点POI ID',
    tag_id     BIGINT       NOT NULL COMMENT '标签ID，对应 tags 表',
    source     VARCHAR(16)  NOT NULL DEFAULT 'LLM' COMMENT '来源: LLM / MANUAL',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (poi_id, tag_id),
    INDEX idx_attraction_tag_tag_id (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='景点-研学标签关联';

-- ============================================================
-- 景点相邻关系表（支持 GEOGRAPHIC + THEMATIC 双类型边）
-- ============================================================
CREATE TABLE IF NOT EXISTS attraction_adjacency (
    from_poi_id     VARCHAR(32)  NOT NULL COMMENT '起点景点POI ID',
    to_poi_id       VARCHAR(32)  NOT NULL COMMENT '终点景点POI ID',
    transit_minutes INT          COMMENT '公交通勤时间(分钟)，NULL表示无公交',
    distance_m      INT          COMMENT '交通距离(米)',
    relation_type   VARCHAR(20)  NOT NULL DEFAULT 'GEOGRAPHIC' COMMENT 'GEOGRAPHIC / THEMATIC',
    similarity_score DOUBLE      DEFAULT NULL COMMENT '语义相似度(0-1)，仅 THEMATIC 有值',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (from_poi_id, to_poi_id, relation_type),
    INDEX idx_adjacency_to (to_poi_id),
    INDEX idx_adjacency_type (relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='景点相邻关系(地理+语义)';

-- ============================================================
-- 领队放弃带队审计记录表
-- ============================================================
CREATE TABLE IF NOT EXISTS project_abandon_records (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    project_id        BIGINT       NOT NULL COMMENT '被放弃的项目ID',
    leader_account_id BIGINT       NOT NULL COMMENT '放弃带队的领队账号ID',
    reason            VARCHAR(200) NOT NULL COMMENT '放弃原因(去首尾空格后5-200字)',
    previous_status   VARCHAR(20)  NOT NULL COMMENT '放弃前的项目状态',
    departure_at      DATETIME     DEFAULT NULL COMMENT '放弃时项目的出发时间快照',
    abandoned_at      DATETIME     NOT NULL COMMENT '放弃操作时间(服务器时间)',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    PRIMARY KEY (id),
    INDEX idx_abandon_records_project (project_id, abandoned_at),
    INDEX idx_abandon_records_leader (leader_account_id, abandoned_at),
    CONSTRAINT fk_abandon_records_project FOREIGN KEY (project_id)
        REFERENCES projects (id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_abandon_records_leader FOREIGN KEY (leader_account_id)
        REFERENCES accounts (id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='领队放弃带队审计记录';
