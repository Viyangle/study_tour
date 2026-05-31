SET NAMES utf8mb4;

SET @has_intro := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'accounts'
      AND column_name = 'intro'
);

SET @ddl := IF(
    @has_intro = 0,
    'ALTER TABLE accounts ADD COLUMN intro VARCHAR(500) NULL COMMENT ''普通用户简介'' AFTER avatar_url',
    'SELECT ''accounts.intro already exists'''
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE accounts
SET intro = CASE id
    WHEN 1 THEN '关注历史建筑与城市文化，喜欢在研学路线中记录细节和心得。'
    WHEN 2 THEN '偏好轻松有序的博物馆和自然观察行程，愿意提前整理资料。'
    WHEN 5 THEN '喜欢把实地参访和课堂知识结合，关注路线的安全与体验感。'
    WHEN 6 THEN '喜欢把城市历史、博物馆展览和实地观察结合起来，偏好节奏清晰的研学路线。'
    WHEN 83 THEN '关注南京本地文化和公共空间体验，习惯为同行伙伴整理行前提醒。'
    WHEN 84 THEN '偏好兼具知识性和互动性的研学活动，重视路线中的讨论和复盘。'
    WHEN 85 THEN '对南京城墙、明都格局和城市变迁感兴趣，喜欢历史人文主题研学。'
    WHEN 86 THEN '喜欢在博物馆中通过文物线索理解历史，参观时会认真做观察笔记。'
    WHEN 87 THEN '对云锦、金箔和传统手作有浓厚兴趣，期待参与沉浸式非遗体验。'
    WHEN 88 THEN '关注科技馆实验、工程展示和创新应用，喜欢带着问题参观学习。'
    WHEN 89 THEN '喜欢湖泊湿地、植物观察和城市生态主题，注重自然记录和环保意识。'
    WHEN 90 THEN '对地层、岩石和地貌演化感兴趣，适合参与户外地质科考路线。'
    WHEN 91 THEN '关注航空航天知识和飞行原理，喜欢模型实践与科普基地参访。'
    WHEN 92 THEN '喜欢农耕劳动和节气课程，愿意在实践中理解食物与土地的关系。'
    WHEN 93 THEN '对美术馆展览、城市建筑和审美表达感兴趣，喜欢用作品记录见闻。'
    WHEN 94 THEN '关注红色地标和革命历史，希望在实地参访中理解精神传承。'
    WHEN 95 THEN '喜欢走进高校校园了解学科方向，关注大学生活与专业选择。'
    WHEN 96 THEN '对职业启蒙和行业参访感兴趣，期待了解真实工作场景和技能要求。'
    WHEN 97 THEN '希望在城市导览中练习英语表达，喜欢双语讲解和情境交流。'
    WHEN 98 THEN '喜欢用照片记录路线中的建筑、人物和自然光影，关注叙事表达。'
    WHEN 99 THEN '偏好亲子协作和任务式参观，喜欢在互动中完成观察和分享。'
    WHEN 100 THEN '关注秦淮街巷、地方掌故和文化遗产，喜欢慢节奏城市漫游。'
    WHEN 101 THEN '喜欢围绕展品做主题探究，参观后会整理关键词和问题清单。'
    WHEN 102 THEN '对传统工艺背后的材料、流程和匠人故事感兴趣，喜欢动手体验。'
    WHEN 103 THEN '关注智能制造、数字技术和城市产业变化，喜欢实地了解新兴职业。'
    WHEN 104 THEN '喜欢森林、公园和生物多样性观察，愿意参与自然保护实践。'
    WHEN 105 THEN '对火山地貌和户外徒步科考感兴趣，重视安全、补给和观察记录。'
    WHEN 106 THEN '喜欢飞行原理、航模制作和航空科普，期待从实践中理解科学概念。'
    WHEN 107 THEN '愿意参加田园劳动和收获体验，关注农业生产与日常生活的连接。'
    WHEN 108 THEN '喜欢书画、展览和城市美育活动，关注作品背后的文化语境。'
    WHEN 109 THEN '希望通过红色研学理解城市记忆，喜欢结合史料和现场讲解学习。'
    WHEN 110 THEN '对高校开放日和工程创新展示感兴趣，喜欢和学长学姐交流经验。'
    WHEN 111 THEN '关注城市规划、传媒和公共服务职业，喜欢了解职业路径和真实案例。'
    WHEN 112 THEN '喜欢博物馆双语讲解和口语实践，希望在表达中提升自信。'
    WHEN 113 THEN '热爱城市摄影和人文记录，喜欢用镜头呈现金陵街巷的细节。'
    WHEN 114 THEN '偏好亲子博物馆任务和团队协作，喜欢把参观变成有目标的探索。'
END
WHERE id IN (
    1, 2, 5, 6, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92,
    93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104,
    105, 106, 107, 108, 109, 110, 111, 112, 113, 114
)
AND role IN ('USER', 'BOTH');
