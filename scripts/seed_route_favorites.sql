-- 为每个可用的普通用户、领队或双重身份账号生成 3 条有实际语义的路线收藏：
-- 1. 优先匹配账号的兴趣标签；
-- 2. 其次匹配账号所在地区；
-- 3. 每个账号所选路线的主题互不重复；
-- 4. 只收藏未包含失效景点且至少有两个有效景点名称的路线。
INSERT IGNORE INTO route_favorites (account_id, route_id, created_at)
SELECT account_id,
       route_id,
       TIMESTAMPADD(
           MINUTE,
           -MOD(account_id * 137 + route_id * 29, 64800),
           CURRENT_TIMESTAMP
       ) AS created_at
FROM (
    SELECT tag_choices.account_id,
           tag_choices.route_id,
           ROW_NUMBER() OVER (
               PARTITION BY tag_choices.account_id
               ORDER BY tag_choices.preference_matched DESC,
                        tag_choices.region_score DESC,
                        MOD(tag_choices.account_id * 43 + tag_choices.tag_id * 17, 101),
                        tag_choices.route_id DESC
           ) AS favorite_rank
    FROM (
        SELECT candidates.account_id,
               candidates.route_id,
               candidates.tag_id,
               candidates.preference_matched,
               candidates.region_score
        FROM (
            SELECT a.id AS account_id,
                   r.id AS route_id,
                   t.id AS tag_id,
                   CASE WHEN pref.account_id IS NULL THEN 0 ELSE 1 END AS preference_matched,
                   CASE
                       WHEN a.region_code = r.region_adcode THEN 2
                       WHEN LEFT(a.region_code, 4) = LEFT(r.region_adcode, 4) THEN 1
                       ELSE 0
                   END AS region_score,
                   ROW_NUMBER() OVER (
                       PARTITION BY a.id, r.tag
                       ORDER BY
                           CASE
                               WHEN a.region_code = r.region_adcode THEN 2
                               WHEN LEFT(a.region_code, 4) = LEFT(r.region_adcode, 4) THEN 1
                               ELSE 0
                           END DESC,
                           MOD(a.id * 31 + r.id * 19, 1009),
                           r.id DESC
                   ) AS route_rank_in_tag
            FROM accounts a
            JOIN routes r
              ON r.tag IS NOT NULL
             AND r.contains_outdated_attractions = 0
            JOIN tags t
              ON t.name = r.tag
            JOIN (
                SELECT route_id
                FROM route_attractions
                WHERE name IS NOT NULL
                  AND TRIM(name) != ''
                GROUP BY route_id
                HAVING COUNT(*) >= 2
            ) usable_route
              ON usable_route.route_id = r.id
            LEFT JOIN account_tag_prefs pref
              ON pref.account_id = a.id
             AND pref.tag_id = t.id
            WHERE a.status = 1
              AND a.role IN ('USER', 'LEADER', 'BOTH')
        ) candidates
        WHERE candidates.route_rank_in_tag = 1
    ) tag_choices
) ranked_favorites
WHERE favorite_rank <= 3;
