-- 南京项目列表性能测试数据集
--
-- 目标规模：
--   * 1,200 个项目（30 个主题 x 8 种研学形式 x 5 类参与群体）
--   * 240 条路线、每条路线 3 个真实南京景点
--   * 180 个普通用户、24 个领队、约 5,400 条项目成员关系
--
-- 适用环境：MySQL 8.0+，执行前应已导入项目完整 schema 和南京景点数据。
-- 可重复执行：脚本只重建手机号 188760/188761 开头账号所发布的合成项目，
--             并清理由旧版脚本产生的“南京测试项目xx”，不会清空业务表。

SET NAMES utf8mb4;
USE study_tour;
SET SESSION cte_max_recursion_depth = 2000;

SELECT GET_LOCK('study_tour_seed_nanjing_projects_v2', 30) INTO @seed_lock_acquired;
SET @assert_sql = IF(
    @seed_lock_acquired = 1,
    'DO 0',
    "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '未能取得填充锁，请稍后重试'"
);
PREPARE assert_stmt FROM @assert_sql;
EXECUTE assert_stmt;
DEALLOCATE PREPARE assert_stmt;

SET @seed_project_count := 1200;
SET @seed_route_count := 240;
SET @seed_user_count := 180;
SET @seed_leader_count := 24;
SET @seed_password_hash := '$2a$10$TyGCL9uuMIS7p4u7VT4u/eP0jnRCM0lpELK9wLbej2v.IHffa9AoC';

START TRANSACTION;

-- -------------------------------------------------------------------------
-- 0. 前置条件：至少要有 3 个启用的南京景点。
-- -------------------------------------------------------------------------
SET @active_nanjing_poi_count := (
    SELECT COUNT(*)
    FROM attractions
    WHERE adcode LIKE '3201%'
      AND COALESCE(status, 'ACTIVE') = 'ACTIVE'
);
SET @assert_sql = IF(
    @active_nanjing_poi_count >= 3,
    'DO 0',
    "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '南京启用景点不足 3 个，请先导入 attractions 数据'"
);
PREPARE assert_stmt FROM @assert_sql;
EXECUTE assert_stmt;
DEALLOCATE PREPARE assert_stmt;

-- -------------------------------------------------------------------------
-- 1. 只清理本脚本数据，以及旧脚本产生的明显测试项目。
-- -------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_cleanup_projects;
CREATE TEMPORARY TABLE tmp_cleanup_projects (
    project_id BIGINT PRIMARY KEY,
    route_id BIGINT NOT NULL,
    KEY idx_tmp_cleanup_route (route_id)
) ENGINE=InnoDB;

INSERT IGNORE INTO tmp_cleanup_projects(project_id, route_id)
SELECT p.id, p.route_id
FROM projects p
JOIN accounts a ON a.id = p.owner_account_id
WHERE a.phone BETWEEN '18876100001' AND '18876100180'
   OR (
       a.phone BETWEEN '19992000001' AND '19992000099'
       AND p.title REGEXP '^南京测试项目[0-9]+$'
   );

DROP TEMPORARY TABLE IF EXISTS tmp_cleanup_routes;
CREATE TEMPORARY TABLE tmp_cleanup_routes (
    route_id BIGINT PRIMARY KEY
) ENGINE=InnoDB;

INSERT IGNORE INTO tmp_cleanup_routes(route_id)
SELECT route_id FROM tmp_cleanup_projects;

DELETE cm
FROM chat_messages cm
JOIN chat_sessions cs ON cs.id = cm.session_id
JOIN tmp_cleanup_projects x ON x.project_id = cs.project_id;

DELETE cgm
FROM chat_group_members cgm
JOIN chat_sessions cs ON cs.id = cgm.group_id
JOIN tmp_cleanup_projects x ON x.project_id = cs.project_id;

DELETE cs
FROM chat_sessions cs
JOIN tmp_cleanup_projects x ON x.project_id = cs.project_id;

DELETE la
FROM leader_applications la
JOIN tmp_cleanup_projects x ON x.project_id = la.project_id;

DELETE rv
FROM reviews rv
JOIN tmp_cleanup_projects x ON x.project_id = rv.project_id;

DELETE pm
FROM project_members pm
JOIN tmp_cleanup_projects x ON x.project_id = pm.project_id;

DELETE p
FROM projects p
JOIN tmp_cleanup_projects x ON x.project_id = p.id;

-- 若有人手工复用了合成路线，则保留该路线，避免误删业务项目。
DELETE r
FROM routes r
JOIN tmp_cleanup_routes x ON x.route_id = r.id
WHERE NOT EXISTS (
    SELECT 1 FROM projects p WHERE p.route_id = r.id
);

-- -------------------------------------------------------------------------
-- 2. 基础维表：主题、形式、参与群体、序号。
-- -------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_numbers;
CREATE TEMPORARY TABLE tmp_numbers (
    n INT PRIMARY KEY
) ENGINE=Memory;

INSERT INTO tmp_numbers(n)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @seed_project_count
)
SELECT n FROM seq;

DROP TEMPORARY TABLE IF EXISTS tmp_topics;
CREATE TEMPORARY TABLE tmp_topics (
    topic_no INT PRIMARY KEY,
    topic_name VARCHAR(64) NOT NULL,
    tag_name VARCHAR(32) NOT NULL
) ENGINE=Memory;

INSERT INTO tmp_topics(topic_no, topic_name, tag_name) VALUES
    (1,  '明城墙脉络与都城格局',       '历史人文'),
    (2,  '六朝文物与金陵记忆',         '博物馆研学'),
    (3,  '云锦织造与传统工艺',         '非遗体验'),
    (4,  '古都建筑与空间美学',         '历史人文'),
    (5,  '玄武湖湿地与候鸟观察',       '自然生态'),
    (6,  '紫金山森林与生物多样性',     '自然生态'),
    (7,  '汤山地层与远古生命',         '地理地质'),
    (8,  '航空工程与飞行原理',         '航天航空'),
    (9,  '农耕节气与乡土生活',         '农耕劳动'),
    (10, '雨花英烈与城市记忆',         '红色教育'),
    (11, '渡江历史与时代选择',         '红色教育'),
    (12, '高校学科与成长规划',         '高校参访'),
    (13, '科技场馆与创新实践',         '科技探索'),
    (14, '秦淮街巷与民俗文化',         '历史人文'),
    (15, '博物馆馆藏与历史线索',       '博物馆研学'),
    (16, '长江岸线与生态保护',         '自然生态'),
    (17, '古典园林与造景智慧',         '艺术美育'),
    (18, '书画艺术与视觉表达',         '艺术美育'),
    (19, '城市摄影与人文记录',         '摄影记录'),
    (20, '职业体验与生涯启蒙',         '职业启蒙'),
    (21, '双语导览与文化传播',         '英语实践'),
    (22, '地方美食与饮食文化',         '亲子互动'),
    (23, '非遗技艺与匠人精神',         '非遗体验'),
    (24, '天文观测与宇宙探索',         '科技探索'),
    (25, '公共艺术与城市更新',         '艺术美育'),
    (26, '交通工程与智慧城市',         '科技探索'),
    (27, '国防教育与和平使命',         '红色教育'),
    (28, '山水诗词与文学行走',         '历史人文'),
    (29, '校园博物馆与学科发现',       '高校参访'),
    (30, '亲子协作与任务探索',         '亲子互动');

DROP TEMPORARY TABLE IF EXISTS tmp_formats;
CREATE TEMPORARY TABLE tmp_formats (
    format_no INT PRIMARY KEY,
    format_name VARCHAR(32) NOT NULL
) ENGINE=Memory;

INSERT INTO tmp_formats(format_no, format_name) VALUES
    (1, '深度研学'),
    (2, '主题探究'),
    (3, '实地寻访'),
    (4, '任务挑战'),
    (5, '观察工作坊'),
    (6, '行走课堂'),
    (7, '周末实践'),
    (8, '沉浸体验');

DROP TEMPORARY TABLE IF EXISTS tmp_audiences;
CREATE TEMPORARY TABLE tmp_audiences (
    audience_no INT PRIMARY KEY,
    audience_name VARCHAR(32) NOT NULL,
    participant_requirement VARCHAR(255) NOT NULL
) ENGINE=Memory;

INSERT INTO tmp_audiences(audience_no, audience_name, participant_requirement) VALUES
    (1, '亲子共学营',   '适合亲子共同参加；请穿着便于步行的鞋服，并提前完成行前阅读。'),
    (2, '初中生探究团', '适合初中阶段学生；请携带笔记工具，按小组完成观察与访谈任务。'),
    (3, '高中生主题课', '适合高中阶段学生；鼓励围绕主题提出问题并完成研学成果展示。'),
    (4, '大学生实践行', '适合大学生及青年学习者；需参与实地调研、讨论和当日复盘。'),
    (5, '教师研修班',   '适合教师与教育工作者；请结合课程目标参与案例研讨与教学设计。');

-- -------------------------------------------------------------------------
-- 3. 账号：用户名采用自然中文姓名；所有合成账号密码均为 123456。
-- -------------------------------------------------------------------------
INSERT INTO accounts(
    role, username, phone, password_hash, region_code, intro,
    status, created_at, updated_at
)
SELECT
    'USER',
    CONCAT(
        ELT(MOD(n - 1, 20) + 1,
            '张','李','王','刘','陈','杨','赵','黄','周','吴',
            '徐','孙','胡','朱','高','林','何','郭','马','罗'),
        ELT(FLOOR((n - 1) / 20) + 1,
            '知远','思齐','若溪','明轩','雨桐','子涵','书宁','嘉禾','安然')
    ),
    CONCAT('188761', LPAD(n, 5, '0')),
    @seed_password_hash,
    '320100',
    ELT(MOD(n - 1, 6) + 1,
        '喜欢把城市历史、建筑观察和行走记录结合起来，关注研学过程中的真实发现。',
        '偏好博物馆、展览与文物主题活动，习惯带着问题参观并整理学习笔记。',
        '关注自然生态与户外观察，希望通过实地体验理解环境保护和生物多样性。',
        '对科技创新、工程实践和职业体验感兴趣，愿意参与团队任务与成果分享。',
        '喜欢传统工艺、艺术表达和地方文化，期待在动手体验中理解匠人精神。',
        '重视团队协作与安全秩序，愿意提前阅读资料并在活动后完成复盘。'
    ),
    1,
    DATE_SUB(NOW(), INTERVAL (180 + MOD(n * 11, 540)) DAY),
    NOW()
FROM tmp_numbers
WHERE n <= @seed_user_count
ON DUPLICATE KEY UPDATE
    role = VALUES(role),
    username = VALUES(username),
    password_hash = VALUES(password_hash),
    region_code = VALUES(region_code),
    intro = VALUES(intro),
    status = 1,
    updated_at = NOW();

INSERT INTO accounts(
    role, username, phone, password_hash, region_code, intro,
    status, created_at, updated_at
)
SELECT
    IF(MOD(n, 6) = 0, 'BOTH', 'LEADER'),
    CONCAT(
        ELT(MOD(n - 1, 12) + 1,
            '沈','韩','宋','唐','冯','曹','彭','潘','袁','邓','苏','傅'),
        ELT(FLOOR((n - 1) / 12) + 1, '景行', '承岳')
    ),
    CONCAT('188760', LPAD(n, 5, '0')),
    @seed_password_hash,
    '320100',
    '熟悉南京历史文化、公共场馆与户外研学路线，重视安全管理、节奏控制和学习复盘。',
    1,
    DATE_SUB(NOW(), INTERVAL (360 + MOD(n * 23, 720)) DAY),
    NOW()
FROM tmp_numbers
WHERE n <= @seed_leader_count
ON DUPLICATE KEY UPDATE
    role = VALUES(role),
    username = VALUES(username),
    password_hash = VALUES(password_hash),
    region_code = VALUES(region_code),
    intro = VALUES(intro),
    status = 1,
    updated_at = NOW();

DROP TEMPORARY TABLE IF EXISTS tmp_seed_users;
CREATE TEMPORARY TABLE tmp_seed_users (
    user_no INT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    KEY idx_tmp_seed_user_account (account_id)
) ENGINE=Memory;

INSERT INTO tmp_seed_users(user_no, account_id)
SELECT CAST(RIGHT(phone, 5) AS UNSIGNED), id
FROM accounts
WHERE phone BETWEEN '18876100001' AND '18876100180';

DROP TEMPORARY TABLE IF EXISTS tmp_seed_leaders;
CREATE TEMPORARY TABLE tmp_seed_leaders (
    leader_no INT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    KEY idx_tmp_seed_leader_account (account_id)
) ENGINE=Memory;

INSERT INTO tmp_seed_leaders(leader_no, account_id)
SELECT CAST(RIGHT(phone, 5) AS UNSIGNED), id
FROM accounts
WHERE phone BETWEEN '18876000001' AND '18876000024';

INSERT INTO leader_profiles(account_id, intro, total_rating, rating_count)
SELECT
    l.account_id,
    '南京本地研学领队，具备场馆讲解、队伍组织和应急沟通经验。',
    84 + MOD(l.leader_no * 7, 32),
    18 + MOD(l.leader_no * 5, 36)
FROM tmp_seed_leaders l
ON DUPLICATE KEY UPDATE
    intro = VALUES(intro),
    total_rating = VALUES(total_rating),
    rating_count = VALUES(rating_count);

-- 每位用户配置 3 个兴趣标签，为知识图谱推荐制造稳定但不完全相同的偏好。
INSERT IGNORE INTO account_tag_prefs(account_id, tag_id)
SELECT
    u.account_id,
    t.id
FROM tmp_seed_users u
CROSS JOIN (
    SELECT 0 AS pref_offset
    UNION ALL SELECT 1
    UNION ALL SELECT 2
) pref
JOIN tags t ON t.name = ELT(
    MOD(u.user_no + pref.pref_offset * 5 - 1, 15) + 1,
    '历史人文','博物馆研学','非遗体验','科技探索','自然生态',
    '地理地质','航天航空','农耕劳动','艺术美育','红色教育',
    '高校参访','职业启蒙','英语实践','摄影记录','亲子互动'
);

-- -------------------------------------------------------------------------
-- 4. 240 条路线，每条绑定 3 个真实南京景点快照。
-- -------------------------------------------------------------------------
INSERT INTO routes(created_at, region_adcode, tag, contains_outdated_attractions)
SELECT
    DATE_SUB(
        DATE_SUB(NOW(), INTERVAL MOD((t.topic_no - 1) * 8 + f.format_no, 240) DAY),
        INTERVAL MOD(((t.topic_no - 1) * 8 + f.format_no) * 137, 86400) SECOND
    ),
    '320100',
    t.tag_name,
    0
FROM tmp_topics t
CROSS JOIN tmp_formats f
ORDER BY t.topic_no, f.format_no;

SET @seed_route_base := LAST_INSERT_ID();

DROP TEMPORARY TABLE IF EXISTS tmp_seed_routes;
CREATE TEMPORARY TABLE tmp_seed_routes (
    route_no INT PRIMARY KEY,
    route_id BIGINT NOT NULL,
    KEY idx_tmp_seed_route_id (route_id)
) ENGINE=Memory;

INSERT INTO tmp_seed_routes(route_no, route_id)
SELECT n, @seed_route_base + n - 1
FROM tmp_numbers
WHERE n <= @seed_route_count;

DROP TEMPORARY TABLE IF EXISTS tmp_nanjing_pois;
CREATE TEMPORARY TABLE tmp_nanjing_pois (
    poi_no INT PRIMARY KEY,
    poi_id VARCHAR(32) NOT NULL,
    KEY idx_tmp_nanjing_poi_id (poi_id)
) ENGINE=Memory;

INSERT INTO tmp_nanjing_pois(poi_no, poi_id)
SELECT ROW_NUMBER() OVER (ORDER BY poi_id), poi_id
FROM attractions
WHERE adcode LIKE '3201%'
  AND COALESCE(status, 'ACTIVE') = 'ACTIVE';

SET @seed_poi_count := (SELECT COUNT(*) FROM tmp_nanjing_pois);

INSERT INTO route_attractions(
    route_id, visit_order, visit_time, recommended_duration, notes,
    poi_id, parent_poi_id, name, address, location, pcode, pname,
    citycode, cityname, adcode, adname, type, typecode, distance,
    opentime_today, opentime_week, tel,
    attraction_created_at, attraction_updated_at
)
SELECT
    sr.route_id,
    slot.visit_order,
    DATE_ADD(
        DATE_ADD(CURDATE(), INTERVAL MOD(sr.route_no, 90) + 7 DAY),
        INTERVAL 8 + (slot.visit_order - 1) * 3 HOUR
    ),
    ELT(slot.visit_order, 120, 90, 110),
    ELT(slot.visit_order,
        '上午核心点位：完成导入讲解、主题观察与小组记录。',
        '中段探究点位：安排任务实践，并预留休息与补给时间。',
        '收束点位：完成成果分享、问题复盘与自由参观。'
    ),
    a.poi_id, a.parent_poi_id, a.name, a.address, a.location, a.pcode, a.pname,
    a.citycode, a.cityname, a.adcode, a.adname, a.type, a.typecode, a.distance,
    a.opentime_today, a.opentime_week, a.tel,
    a.created_at, a.updated_at
FROM tmp_seed_routes sr
CROSS JOIN (
    SELECT 1 AS visit_order
    UNION ALL SELECT 2
    UNION ALL SELECT 3
) slot
JOIN tmp_nanjing_pois np
  ON np.poi_no = MOD(sr.route_no * 7 + slot.visit_order * 13 - 1, @seed_poi_count) + 1
JOIN attractions a ON a.poi_id = np.poi_id;

-- -------------------------------------------------------------------------
-- 5. 1,200 个项目。标题来自业务主题组合，不含“测试项目+编号”。
-- -------------------------------------------------------------------------
INSERT INTO projects(
    route_id, owner_account_id, leader_account_id, title,
    departure_date, departure_time, start_point_type, start_point,
    leader_requirements, participant_requirements,
    max_members, current_members, status, created_at, updated_at,
    region_adcode, tag
)
SELECT
    sr.route_id,
    owner.account_id,
    CASE
        WHEN MOD(((aud.audience_no - 1) * 240 + sr.route_no) - 1, 20) < 12 THEN NULL
        ELSE leader.account_id
    END,
    CONCAT(t.topic_name, f.format_name, '·', aud.audience_name),
    CASE
        WHEN MOD(((aud.audience_no - 1) * 240 + sr.route_no) - 1, 20) < 16
            THEN DATE_ADD(CURDATE(), INTERVAL 3 + MOD(((aud.audience_no - 1) * 240 + sr.route_no) * 7, 178) DAY)
        WHEN MOD(((aud.audience_no - 1) * 240 + sr.route_no) - 1, 20) = 16
            THEN CURDATE()
        WHEN MOD(((aud.audience_no - 1) * 240 + sr.route_no) - 1, 20) < 19
            THEN DATE_SUB(CURDATE(), INTERVAL 3 + MOD(((aud.audience_no - 1) * 240 + sr.route_no) * 11, 180) DAY)
        ELSE DATE_ADD(CURDATE(), INTERVAL 10 + MOD(((aud.audience_no - 1) * 240 + sr.route_no) * 5, 90) DAY)
    END,
    ADDTIME('07:30:00', SEC_TO_TIME(MOD(sr.route_no, 7) * 900)),
    'MANUAL',
    ELT(MOD(sr.route_no - 1, 10) + 1,
        '南京南站北广场', '新街口地铁站24号口', '玄武门地铁站3号口',
        '大行宫地铁站5号口', '鸡鸣寺地铁站5号口', '夫子庙东牌坊',
        '南京博物院南门', '奥体中心东门', '鼓楼广场西侧', '雨花台南门游客中心'
    ),
    ELT(MOD(sr.route_no - 1, 5) + 1,
        '熟悉南京城市历史与公共场馆，能够组织主题讲解和小组探究。',
        '具备研学团队带队经验，重视交通衔接、人员清点与安全预案。',
        '善于引导观察、提问和成果分享，能够根据年龄调整讲解深度。',
        '了解场馆预约与户外活动规范，能够处理临时行程变化。',
        '具备良好的沟通和急救意识，能够组织行前说明与活动复盘。'
    ),
    aud.participant_requirement,
    18 + MOD(((aud.audience_no - 1) * 240 + sr.route_no) * 7, 25),
    CASE
        WHEN MOD(((aud.audience_no - 1) * 240 + sr.route_no) - 1, 20) = 19 THEN 0
        ELSE 2 + MOD((aud.audience_no - 1) * 240 + sr.route_no, 6)
             + IF(MOD((aud.audience_no - 1) * 240 + sr.route_no, 3) = 0, 1, 0)
    END,
    CASE
        WHEN MOD(((aud.audience_no - 1) * 240 + sr.route_no) - 1, 20) < 8 THEN 'OPEN'
        WHEN MOD(((aud.audience_no - 1) * 240 + sr.route_no) - 1, 20) < 12 THEN 'MATCHING'
        WHEN MOD(((aud.audience_no - 1) * 240 + sr.route_no) - 1, 20) < 16 THEN 'CONFIRMED'
        WHEN MOD(((aud.audience_no - 1) * 240 + sr.route_no) - 1, 20) = 16 THEN 'IN_PROGRESS'
        WHEN MOD(((aud.audience_no - 1) * 240 + sr.route_no) - 1, 20) < 19 THEN 'DONE'
        ELSE 'CANCELLED'
    END,
    DATE_SUB(
        DATE_SUB(NOW(), INTERVAL MOD(((aud.audience_no - 1) * 240 + sr.route_no) * 17, 365) DAY),
        INTERVAL MOD(((aud.audience_no - 1) * 240 + sr.route_no) * 97, 86400) SECOND
    ),
    NOW(),
    '320100',
    t.tag_name
FROM tmp_seed_routes sr
JOIN tmp_topics t ON t.topic_no = FLOOR((sr.route_no - 1) / 8) + 1
JOIN tmp_formats f ON f.format_no = MOD(sr.route_no - 1, 8) + 1
CROSS JOIN tmp_audiences aud
JOIN tmp_seed_users owner
  ON owner.user_no = MOD((aud.audience_no - 1) * 240 + sr.route_no - 1, @seed_user_count) + 1
JOIN tmp_seed_leaders leader
  ON leader.leader_no = MOD((aud.audience_no - 1) * 240 + sr.route_no - 1, @seed_leader_count) + 1
ORDER BY aud.audience_no, sr.route_no;

SET @seed_project_base := LAST_INSERT_ID();

DROP TEMPORARY TABLE IF EXISTS tmp_seed_projects;
CREATE TEMPORARY TABLE tmp_seed_projects (
    seed_no INT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    KEY idx_tmp_seed_project_id (project_id)
) ENGINE=Memory;

INSERT INTO tmp_seed_projects(seed_no, project_id)
SELECT n, @seed_project_base + n - 1
FROM tmp_numbers
WHERE n <= @seed_project_count;

-- 发布者关系。每位发布者代表 1 人，部分项目代表 2 人。
INSERT INTO project_members(
    project_id, account_id, join_status, represented_count, joined_at
)
SELECT
    sp.project_id,
    owner.account_id,
    IF(p.status = 'CANCELLED', 'QUIT', IF(p.status = 'DONE', 'COMPLETED', 'JOINED')),
    1 + IF(MOD(sp.seed_no, 3) = 0, 1, 0),
    DATE_ADD(p.created_at, INTERVAL 1 HOUR)
FROM tmp_seed_projects sp
JOIN projects p ON p.id = sp.project_id
JOIN tmp_seed_users owner
  ON owner.user_no = MOD(sp.seed_no - 1, @seed_user_count) + 1;

-- 每个项目再加入 1~6 个参与账号，使成员表达到数千条并形成协同过滤关系。
INSERT INTO project_members(
    project_id, account_id, join_status, represented_count, joined_at
)
SELECT
    sp.project_id,
    member_account.account_id,
    IF(p.status = 'CANCELLED', 'QUIT', IF(p.status = 'DONE', 'COMPLETED', 'JOINED')),
    1,
    DATE_ADD(p.created_at, INTERVAL member_slot.slot_no DAY)
FROM tmp_seed_projects sp
JOIN projects p ON p.id = sp.project_id
CROSS JOIN (
    SELECT 1 AS slot_no
    UNION ALL SELECT 2
    UNION ALL SELECT 3
    UNION ALL SELECT 4
    UNION ALL SELECT 5
    UNION ALL SELECT 6
) member_slot
JOIN tmp_seed_users member_account
  ON member_account.user_no = MOD(sp.seed_no + member_slot.slot_no * 23 - 1, @seed_user_count) + 1
WHERE member_slot.slot_no <= 1 + MOD(sp.seed_no, 6);

-- MATCHING 项目带一条待处理领队申请；DONE 项目带两条自然评价。
INSERT INTO leader_applications(
    project_id, leader_account_id, status, message, created_at, updated_at
)
SELECT
    sp.project_id,
    l.account_id,
    'PENDING',
    '熟悉该主题路线，可负责行前沟通、现场组织和活动复盘。',
    DATE_SUB(NOW(), INTERVAL MOD(sp.seed_no, 10) DAY),
    NOW()
FROM tmp_seed_projects sp
JOIN projects p ON p.id = sp.project_id AND p.status = 'MATCHING'
JOIN tmp_seed_leaders l ON l.leader_no = MOD(sp.seed_no + 3, @seed_leader_count) + 1;

INSERT INTO reviews(
    project_id, route_id, from_account_id, to_account_id,
    review_type, overall_score, content, created_at
)
SELECT
    sp.project_id,
    p.route_id,
    reviewer.account_id,
    p.leader_account_id,
    'USER_TO_LEADER',
    IF(MOD(sp.seed_no + review_slot.slot_no, 4) = 0, 4, 5),
    ELT(review_slot.slot_no,
        '讲解层次清楚，时间安排合理，小组任务与主题结合得很好。',
        '路线衔接顺畅，现场组织细致，活动后的总结也很有帮助。'
    ),
    DATE_ADD(p.departure_date, INTERVAL review_slot.slot_no DAY)
FROM tmp_seed_projects sp
JOIN projects p ON p.id = sp.project_id AND p.status = 'DONE'
CROSS JOIN (
    SELECT 1 AS slot_no
    UNION ALL SELECT 2
) review_slot
JOIN tmp_seed_users reviewer
  ON reviewer.user_no = MOD(sp.seed_no + review_slot.slot_no * 23 - 1, @seed_user_count) + 1;

-- -------------------------------------------------------------------------
-- 6. 事务内验收；任何核心数量不符都会回滚。
-- -------------------------------------------------------------------------
SET @actual_seed_projects := (
    SELECT COUNT(*)
    FROM projects p
    JOIN accounts a ON a.id = p.owner_account_id
    WHERE a.phone BETWEEN '18876100001' AND '18876100180'
);
SET @actual_seed_routes := (
    SELECT COUNT(*) FROM tmp_seed_routes
);
SET @actual_seed_project_members := (
    SELECT COUNT(*)
    FROM project_members pm
    JOIN tmp_seed_projects sp ON sp.project_id = pm.project_id
);

SET @assert_sql = IF(
    @actual_seed_projects = @seed_project_count
    AND @actual_seed_routes = @seed_route_count
    AND @actual_seed_project_members >= 5000,
    'DO 0',
    "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '填充数量验收失败，事务已回滚'"
);
PREPARE assert_stmt FROM @assert_sql;
EXECUTE assert_stmt;
DEALLOCATE PREPARE assert_stmt;

COMMIT;
DO RELEASE_LOCK('study_tour_seed_nanjing_projects_v2');

-- -------------------------------------------------------------------------
-- 7. 可直接保存到报告中的数据规模与分布摘要。
-- -------------------------------------------------------------------------
SELECT 'accounts_total' AS metric, COUNT(*) AS value FROM accounts
UNION ALL SELECT 'routes_total', COUNT(*) FROM routes
UNION ALL SELECT 'route_attractions_total', COUNT(*) FROM route_attractions
UNION ALL SELECT 'projects_total', COUNT(*) FROM projects
UNION ALL SELECT 'project_members_total', COUNT(*) FROM project_members
UNION ALL SELECT 'seed_projects', COUNT(*)
          FROM projects p JOIN accounts a ON a.id = p.owner_account_id
          WHERE a.phone BETWEEN '18876100001' AND '18876100180';

SELECT p.status, COUNT(*) AS project_count
FROM projects p
JOIN accounts a ON a.id = p.owner_account_id
WHERE a.phone BETWEEN '18876100001' AND '18876100180'
GROUP BY p.status
ORDER BY FIELD(p.status, 'OPEN','MATCHING','CONFIRMED','IN_PROGRESS','DONE','CANCELLED');

SELECT p.tag, COUNT(*) AS project_count
FROM projects p
JOIN accounts a ON a.id = p.owner_account_id
WHERE a.phone BETWEEN '18876100001' AND '18876100180'
GROUP BY p.tag
ORDER BY project_count DESC, p.tag;

SELECT p.id, p.title, p.tag, p.status, p.departure_date, p.current_members, p.max_members
FROM projects p
JOIN accounts a ON a.id = p.owner_account_id
WHERE a.phone BETWEEN '18876100001' AND '18876100180'
ORDER BY p.id
LIMIT 12;
