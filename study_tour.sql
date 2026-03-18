-- MySQL dump 10.13  Distrib 8.0.43, for Win64 (x86_64)
-- Study Tour Database Schema
-- Properly formatted with correct UTF-8 encoding

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

-- Create database
DROP DATABASE IF EXISTS study_tour;
CREATE DATABASE study_tour CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE study_tour;

-- =====================================================
-- Table: accounts
-- =====================================================
DROP TABLE IF EXISTS `accounts`;
CREATE TABLE `accounts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role` enum('USER','LEADER') NOT NULL DEFAULT 'USER',
  `username` varchar(50) NOT NULL,
  `phone` varchar(20) NOT NULL UNIQUE,
  `password_hash` varchar(255) NOT NULL,
  `region_code` varchar(20) NOT NULL,
  `avatar_url` varchar(255) DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `accounts` VALUES
(1,'USER','V','15151091129','123456','210000','https://study-tour-image.oss-cn-beijing.aliyuncs.com/28afeeb9-afa8-4e16-99e2-4f8aac53a5c3.jpg',1,'2026-03-11 13:42:05','2026-03-13 11:00:28'),
(2,'USER','Luii','10010001000','123456','210000',NULL,1,'2026-03-12 16:53:01','2026-03-12 16:53:01'),
(3,'LEADER','Leader','10010001001','123456','210000',NULL,1,'2026-03-11 15:48:30','2026-03-11 15:48:30');

-- =====================================================
-- Table: tags
-- =====================================================
DROP TABLE IF EXISTS `tags`;
CREATE TABLE `tags` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL UNIQUE,
  `type` enum('INTEREST','ROUTE_STYLE','CROWD','SCENIC') NOT NULL DEFAULT 'INTEREST',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=46 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `tags` VALUES
(1,'历史人文','INTEREST','2026-03-11 14:25:30'),
(2,'博物馆研学','INTEREST','2026-03-11 14:25:30'),
(3,'非遗体验','INTEREST','2026-03-11 14:25:30'),
(4,'科技探索','INTEREST','2026-03-11 14:25:30'),
(5,'自然生态','INTEREST','2026-03-11 14:25:30'),
(6,'地理地质','INTEREST','2026-03-11 14:25:30'),
(7,'航天航空','INTEREST','2026-03-11 14:25:30'),
(8,'农耕劳动','INTEREST','2026-03-11 14:25:30'),
(9,'艺术美育','INTEREST','2026-03-11 14:25:30'),
(10,'红色教育','INTEREST','2026-03-11 14:25:30'),
(11,'高校参访','INTEREST','2026-03-11 14:25:30'),
(12,'职业启蒙','INTEREST','2026-03-11 14:25:30'),
(13,'英语实践','INTEREST','2026-03-11 14:25:30'),
(14,'摄影记录','INTEREST','2026-03-11 14:25:30'),
(15,'亲子互动','INTEREST','2026-03-11 14:25:30'),
(16,'深度研究','ROUTE_STYLE','2026-03-11 14:25:30'),
(17,'轻松休闲','ROUTE_STYLE','2026-03-11 14:25:30'),
(18,'城市漫游','ROUTE_STYLE','2026-03-11 14:25:30'),
(19,'郊野徒步','ROUTE_STYLE','2026-03-11 14:25:30'),
(20,'营地露营','ROUTE_STYLE','2026-03-11 14:25:30'),
(21,'户外挑战','ROUTE_STYLE','2026-03-11 14:25:30'),
(22,'夜游体验','ROUTE_STYLE','2026-03-11 14:25:30'),
(23,'多城联动','ROUTE_STYLE','2026-03-11 14:25:30'),
(24,'单城深化','ROUTE_STYLE','2026-03-11 14:25:30'),
(25,'主题线路','ROUTE_STYLE','2026-03-11 14:25:30'),
(26,'小学生低年级','CROWD','2026-03-11 14:25:30'),
(27,'小学生高年级','CROWD','2026-03-11 14:25:30'),
(28,'初中生','CROWD','2026-03-11 14:25:30'),
(29,'高中生','CROWD','2026-03-11 14:25:30'),
(30,'大学生','CROWD','2026-03-11 14:25:30'),
(31,'亲子家庭','CROWD','2026-03-11 14:25:30'),
(32,'班级团体','CROWD','2026-03-11 14:25:30'),
(33,'公司团建','CROWD','2026-03-11 14:25:30'),
(34,'银发人群','CROWD','2026-03-11 14:25:30'),
(35,'新手友好','CROWD','2026-03-11 14:25:30'),
(36,'5A景区','SCENIC','2026-03-11 14:25:30'),
(37,'历史古镇','SCENIC','2026-03-11 14:25:30'),
(38,'红色基地','SCENIC','2026-03-11 14:25:30'),
(39,'科技馆','SCENIC','2026-03-11 14:25:30'),
(40,'天文馆','SCENIC','2026-03-11 14:25:30'),
(41,'动物园','SCENIC','2026-03-11 14:25:30'),
(42,'海滨海岛','SCENIC','2026-03-11 14:25:30'),
(43,'山地森林','SCENIC','2026-03-11 14:25:30'),
(44,'湖泊湿地','SCENIC','2026-03-11 14:25:30'),
(45,'校园博物馆','SCENIC','2026-03-11 14:25:30');

-- =====================================================
-- Table: account_tag_prefs
-- =====================================================
DROP TABLE IF EXISTS `account_tag_prefs`;
CREATE TABLE `account_tag_prefs` (
  `account_id` bigint NOT NULL,
  `tag_id` bigint NOT NULL,
  PRIMARY KEY (`account_id`,`tag_id`),
  KEY `fk_tag_id` (`tag_id`),
  CONSTRAINT `account_tag_prefs_ibfk_1` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`) ON DELETE CASCADE,
  CONSTRAINT `account_tag_prefs_ibfk_2` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `account_tag_prefs` VALUES (1,1),(1,2);

-- =====================================================
-- Table: leader_profiles
-- =====================================================
DROP TABLE IF EXISTS `leader_profiles`;
CREATE TABLE `leader_profiles` (
  `account_id` bigint NOT NULL,
  `intro` varchar(500) DEFAULT NULL,
  `total_rating` int DEFAULT '0',
  `rating_count` int DEFAULT '0',
  PRIMARY KEY (`account_id`),
  CONSTRAINT `leader_profiles_ibfk_1` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `leader_profiles` VALUES (3,'test',NULL,NULL);

-- =====================================================
-- Table: attractions
-- =====================================================
DROP TABLE IF EXISTS `attractions`;
CREATE TABLE `attractions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `type` enum('MUSEUM','HISTORIC_SITE','NATURAL_SCENERY','THEME_PARK','UNIVERSITY','RED_TOURISM','SCIENCE_CENTER','OTHER') NOT NULL DEFAULT 'OTHER',
  `location` varchar(255) NOT NULL,
  `region_code` varchar(20) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `recommended_duration` int DEFAULT NULL COMMENT '推荐游览时长(分钟)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_attraction_region` (`region_code`),
  KEY `idx_attraction_type` (`type`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `attractions` VALUES
(1,'北京故宫博物馆','HISTORIC_SITE','北京市东城区景山前街4号','110101','明清皇家宫殿建筑群，适合开展古代政治制度与文物保护研究。',240,'2026-03-15 16:01:32','2026-03-15 17:31:26'),
(2,'中国国家博物馆','HISTORIC_SITE','北京市东城区东长安街16号','110101','国家级综合博物馆，适合中国通史与近现代史主题学习。',180,'2026-03-15 16:01:32','2026-03-15 17:31:30'),
(3,'中国科学技术馆','SCIENCE_CENTER','北京市朝阳区北辰东路5号','110105','互动展项丰富，适合科学探究、工程实践与STEM任务式学习。',240,'2026-03-15 16:01:32','2026-03-15 17:31:34'),
(4,'秦始皇帝陵博物馆(兵马俑)','HISTORIC_SITE','陕西省西安市临渭区秦陵北路','610115','秦代考古与古代军事文明核心点位，适合考古方法研究。',240,'2026-03-15 16:01:32','2026-03-15 17:31:37'),
(5,'西安碑林博物馆','HISTORIC_SITE','陕西省西安市碑林区三学街15号','610103','石刻与书法文献集中，适合文字演变与艺术史学习。',150,'2026-03-15 16:01:32','2026-03-15 17:31:40'),
(6,'南京博物馆','HISTORIC_SITE','江苏省南京市玄武区中山东路321号','320102','区域文明史资源丰富，适合分层次的综合历史研究。',210,'2026-03-15 16:01:32','2026-03-15 17:31:43'),
(7,'侵华日军南京大屠杀遇难同胞纪念馆','RED_TOURISM','江苏省南京市建邺区水西门大街418号','320105','以和平教育和历史记忆为核心的专题纪念场馆。',180,'2026-03-15 16:01:32','2026-03-15 17:31:46'),
(8,'上海天文馆','SCIENCE_CENTER','上海市浦东新区临港大道80号','310115','以天文学与宇宙科学为核心，适合科学素养与探究式学习。',210,'2026-03-15 16:01:32','2026-03-15 17:31:49'),
(9,'中国航海博物馆','SCIENCE_CENTER','上海市浦东新区申港大道197号','310115','航海史与船舶工程结合，适合海洋文明与工程主题研究。',180,'2026-03-15 16:01:32','2026-03-15 17:31:51'),
(10,'苏州博物馆','HISTORIC_SITE','江苏省苏州市姑苏区东北街204号','320508','江南文化与现代博物馆建筑融合，适合审美与地方史课程。',150,'2026-03-15 16:01:32','2026-03-15 17:31:55'),
(11,'成都大熊猫繁育研究基地','NATURAL_SCENERY','四川省成都市成华区大熊猫大道375号','510107','生物多样性与生态保护教育核心场景。',240,'2026-03-15 16:01:32','2026-03-15 17:31:58'),
(12,'都江堰景区','NATURAL_SCENERY','四川省成都市都江堰市公园路','510181','世界级古代水利工程，适合地理与工程综合研究。',210,'2026-03-15 16:01:32','2026-03-15 17:32:01'),
(13,'湖北省博物馆','HISTORIC_SITE','湖北省武汉市武昌区东湖路160号','420106','楚文化与青铜乐器专题突出，适合考古与礼乐制度学习。',180,'2026-03-15 16:01:32','2026-03-15 17:32:04'),
(14,'三星堆博物馆','HISTORIC_SITE','四川省德阳市广汉市西安路133号','510681','古蜀文明核心遗址博物馆，适合文明比较与考古主题研究。',210,'2026-03-15 16:01:32','2026-03-15 17:32:07'),
(15,'中国铁路博物馆(东馆)','SCIENCE_CENTER','北京市朝阳区酒给桥北路1号院北侧','110105','铁路工业装备与交通科技史展示完整，适合工程认知课程。',150,'2026-03-15 16:01:32','2026-03-15 17:32:10');

-- =====================================================
-- Table: routes
-- =====================================================
DROP TABLE IF EXISTS `routes`;
CREATE TABLE `routes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `routes` VALUES
(1,'2026-03-11 20:26:30'),
(2,'2026-03-12 13:54:50'),
(4,'2026-03-15 17:34:04');

-- =====================================================
-- Table: route_attractions
-- =====================================================
DROP TABLE IF EXISTS `route_attractions`;
CREATE TABLE `route_attractions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `route_id` bigint NOT NULL,
  `attraction_id` bigint NOT NULL,
  `visit_order` int NOT NULL DEFAULT '1',
  `visit_time` datetime DEFAULT NULL,
  `recommended_duration` int NOT NULL,
  `notes` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_route_attraction` (`route_id`,`attraction_id`,`visit_order`),
  KEY `fk_attraction_id` (`attraction_id`),
  KEY `idx_route_attraction_route` (`route_id`),
  KEY `idx_route_attraction_order` (`visit_order`),
  CONSTRAINT `route_attractions_ibfk_1` FOREIGN KEY (`route_id`) REFERENCES `routes` (`id`) ON DELETE CASCADE,
  CONSTRAINT `route_attractions_ibfk_2` FOREIGN KEY (`attraction_id`) REFERENCES `attractions` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `route_attractions` VALUES
(11,4,1,1,'2023-10-01 09:00:00',180,'避免早高峰，预留安排时间'),
(12,4,2,2,'2023-10-01 14:00:00',180,'建议错峰安排两个不同日期或半天/半天'),
(13,4,3,3,'2023-10-02 09:00:00',180,'提前预约并控制单批人数'),
(14,4,15,4,'2023-10-02 14:00:00',180,'每20-25人配1位讲解；带队');

-- =====================================================
-- Table: projects
-- =====================================================
DROP TABLE IF EXISTS `projects`;
CREATE TABLE `projects` (
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
  PRIMARY KEY (`id`),
  KEY `fk_route_id` (`route_id`),
  KEY `fk_owner_account_id` (`owner_account_id`),
  KEY `fk_leader_account_id` (`leader_account_id`),
  KEY `idx_project_status` (`status`),
  KEY `idx_project_departure` (`departure_date`),
  CONSTRAINT `projects_ibfk_1` FOREIGN KEY (`route_id`) REFERENCES `routes` (`id`) ON DELETE CASCADE,
  CONSTRAINT `projects_ibfk_2` FOREIGN KEY (`owner_account_id`) REFERENCES `accounts` (`id`),
  CONSTRAINT `projects_ibfk_3` FOREIGN KEY (`leader_account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `projects` VALUES
(1,2,1,3,'test','2026-03-12',3,1,'OPEN','2026-03-12 16:38:12','2026-03-12 19:56:33');

-- =====================================================
-- Table: project_members
-- =====================================================
DROP TABLE IF EXISTS `project_members`;
CREATE TABLE `project_members` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `account_id` bigint NOT NULL,
  `join_status` enum('JOINED','QUIT','KICKED','COMPLETED') NOT NULL DEFAULT 'JOINED',
  `joined_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_member` (`project_id`,`account_id`),
  KEY `fk_account_id` (`account_id`),
  CONSTRAINT `project_members_ibfk_1` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE CASCADE,
  CONSTRAINT `project_members_ibfk_2` FOREIGN KEY (`account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `project_members` VALUES
(1,1,1,'JOINED','2026-03-12 16:38:12'),
(2,1,2,'JOINED','2026-03-12 19:18:53');

-- =====================================================
-- Table: chat_sessions
-- =====================================================
DROP TABLE IF EXISTS `chat_sessions`;
CREATE TABLE `chat_sessions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `user_account_id` bigint NOT NULL,
  `leader_account_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_chat` (`project_id`,`user_account_id`,`leader_account_id`),
  KEY `fk_user_account_id` (`user_account_id`),
  KEY `fk_leader_account_id` (`leader_account_id`),
  CONSTRAINT `chat_sessions_ibfk_1` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chat_sessions_ibfk_2` FOREIGN KEY (`user_account_id`) REFERENCES `accounts` (`id`),
  CONSTRAINT `chat_sessions_ibfk_3` FOREIGN KEY (`leader_account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =====================================================
-- Table: chat_messages
-- =====================================================
DROP TABLE IF EXISTS `chat_messages`;
CREATE TABLE `chat_messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `sender_account_id` bigint NOT NULL,
  `content` text NOT NULL,
  `msg_type` enum('TEXT','IMAGE','SYSTEM') NOT NULL DEFAULT 'TEXT',
  `sent_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_sender_account_id` (`sender_account_id`),
  KEY `idx_msg_session_time` (`session_id`,`sent_at`),
  CONSTRAINT `chat_messages_ibfk_1` FOREIGN KEY (`session_id`) REFERENCES `chat_sessions` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chat_messages_ibfk_2` FOREIGN KEY (`sender_account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =====================================================
-- Table: reviews
-- =====================================================
DROP TABLE IF EXISTS `reviews`;
CREATE TABLE `reviews` (
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
  KEY `fk_project_id` (`project_id`),
  KEY `fk_from_account_id` (`from_account_id`),
  KEY `idx_review_to` (`to_account_id`),
  KEY `idx_review_route` (`route_id`),
  CONSTRAINT `reviews_ibfk_1` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `reviews_ibfk_2` FOREIGN KEY (`route_id`) REFERENCES `routes` (`id`),
  CONSTRAINT `reviews_ibfk_3` FOREIGN KEY (`from_account_id`) REFERENCES `accounts` (`id`),
  CONSTRAINT `reviews_ibfk_4` FOREIGN KEY (`to_account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =====================================================
-- Table: review_tag_scores
-- =====================================================
DROP TABLE IF EXISTS `review_tag_scores`;
CREATE TABLE `review_tag_scores` (
  `review_id` bigint NOT NULL,
  `tag_id` bigint NOT NULL,
  `score` tinyint NOT NULL,
  PRIMARY KEY (`review_id`,`tag_id`),
  KEY `fk_tag_id` (`tag_id`),
  CONSTRAINT `review_tag_scores_ibfk_1` FOREIGN KEY (`review_id`) REFERENCES `reviews` (`id`) ON DELETE CASCADE,
  CONSTRAINT `review_tag_scores_ibfk_2` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =====================================================
-- Table: route_tags
-- =====================================================
DROP TABLE IF EXISTS `route_tags`;
CREATE TABLE `route_tags` (
  `route_id` bigint NOT NULL,
  `tag_id` bigint NOT NULL,
  PRIMARY KEY (`route_id`,`tag_id`),
  KEY `fk_tag_id` (`tag_id`),
  CONSTRAINT `route_tags_ibfk_1` FOREIGN KEY (`route_id`) REFERENCES `routes` (`id`) ON DELETE CASCADE,
  CONSTRAINT `route_tags_ibfk_2` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =====================================================
-- Table: leader_applications
-- =====================================================
DROP TABLE IF EXISTS `leader_applications`;
CREATE TABLE `leader_applications` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `leader_account_id` bigint NOT NULL,
  `status` enum('PENDING','ACCEPTED','REJECTED','CANCELLED') NOT NULL DEFAULT 'PENDING',
  `message` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_leader` (`project_id`,`leader_account_id`),
  KEY `fk_leader_account_id` (`leader_account_id`),
  CONSTRAINT `leader_applications_ibfk_1` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE CASCADE,
  CONSTRAINT `leader_applications_ibfk_2` FOREIGN KEY (`leader_account_id`) REFERENCES `accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;
/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed

