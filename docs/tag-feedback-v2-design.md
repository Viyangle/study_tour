# 标签反馈 2.0：把「景点属性」和「用户选择」分开

> 状态：核心链路已实现（V6 学习层/曝光/排序模型、serve-mode、pairwise 训练工具）；灰度未做<br>
> 关联：[attraction-tag-vector-design.md](./attraction-tag-vector-design.md)、[eval_feedback/CV_REPORT.md](../eval_feedback/CV_REPORT.md)<br>
> 一句话：**15 维景点向量继续保留，但不再把「一次会话里用户选了什么」直接写成「这个景点的全局 tag 权重」。**

## 0. 结论先行

当前实现 `s_i = (κ_i·p_i + pos_i) / (κ_i + pos_i + neg_i)` 本身没有数学错误，它是标准的 Beta 后验均值。问题在于 `pos/neg` 的语义：

- 用户选择一条路线，不只因为「景点和 tag 匹配」，还因为距离、通勤、开放时间、同行人、天气、新鲜感、路线顺序、当天体力、曝光位置；
- 同一个人这次选「博物馆」不代表下次还选，不同用户对同一个 tag 的偏好也不同；
- 把一次会话的最终选择按 `+0.8` 写进全局 `attraction_tag`，等于用「会话级标签」去估计「景点级属性」，目标函数就错了。

[`CV_REPORT.md`](../eval_feedback/CV_REPORT.md) 的结果正好是这个错误的症状：最终站点 vs 过程淘汰站点的 pairwise accuracy 从 0.090 升到 0.313，但整体 NDCG@5 从 0.0980 降到 0.0881，graded NDCG 也全线下降。**反馈确实在学，但学的是「这次会话选了什么」，不是「景点适合什么」。**

所以 2.0 的核心不是换一个更复杂的公式，而是把目标拆成三层，分别学习、分别服务、分别评估。

## 1. 目标与非目标

### 目标

1. `p_a`：景点 15 维语义先验，反馈不直接改写。
2. `h_a`：全局「选择热度/适配度」学习层，和先验分开存，按支持度收缩。
3. `u_session` / `u_user`：会话/用户级偏好，只影响当前用户当前排序，不写全局。
4. 排序时显式加入距离、时长、热度、评价等非 tag 特征，不再让 tag 匹配独自决定。
5. 能被离线时间切分评估、能 shadow、能回滚。

### 非目标

- 不把 15 维扩成 45 维；维度不是当前瓶颈。
- 不上深度排序模型；数据量不足，先用可解释的 pairwise 线性模型/LightGBM。
- 不做实时在线训练；先离线训练、在线只做特征拼接和打分。
- 不追求「每个用户一套全局 tag」；用户偏好放在用户层，不污染景点层。

## 2. 三层模型

### 2.1 三层定义

| 层 | 记号 | 来源 | 更新频率 | 作用范围 |
|---|---|---|---|---|
| 语义先验 | `p_a ∈ [0,1]^15` | 人工/LLM/规则 | 离线重标 | 全局，只读 |
| 全局学习 | `h_a ∈ [0,1]^15` | 跨用户选择/拒绝，曝光归一化 | 日级 | 全局，带支持度 |
| 会话偏好 | `u_s ∈ [0,1]^15` | 本次筛选、AI interests、最近点击 | 秒级 | 当前会话 |
| 用户偏好 | `u_u ∈ [0,1]^15` | `account_tag_prefs` + 历史行为 | 小时/日级 | 当前用户 |

### 2.2 服务公式

先做特征，再做一个可解释的线性融合；权重用离线 pairwise 训练，不手拍：

```text
priorMatch(a)    = Σ_i u_i · p_ai / Σ_i u_i
learnedMatch(a)  = Σ_i u_i · h_ai / Σ_i u_i
popularity(a)    = log1p(unique_users_30d(a)) / log1p(N_max)
sessionMatch(a)  = cosine(u_s, p_a)
quality(a)       = review_avg(a) / 5
distance(a, s)   = 归一化通勤时间 / 60min

score(a | u, s) =
    β0
  + β1 · priorMatch(a)
  + β2 · learnedMatch(a)
  + β3 · popularity(a)
  + β4 · sessionMatch(a)
  + β5 · quality(a)
  - β6 · distance(a, s)
  - β7 · rejectRisk(a | u)
```

`β` 的初始化可以先用人工权重（例如 `0.45 / 0.20 / 0.15 / 0.15 / 0.05 / 0.10`），然后用离线 pairwise 逻辑回归或 LightGBM 校准；每次只改 `β`，不改数据结构。

### 2.3 全局学习层的收缩

不要让一条会话直接改全局。对每个 `(poi, tag)`：

```text
h_i = (pos_i + α0 · p_i) / (pos_i + neg_i + α0)     # 向先验收缩的后验
n_i = distinct_users_i                               # 不同用户数，不是事件数
w_i = n_i / (n_i + κ_user)                          # 支持度权重
s_i = (1 - w_i) · p_i + w_i · h_i
```

- `κ_user` 建议 5~10：至少 5 个不同用户开始明显生效；
- `w_i` 上限先设 0.3，也就是**任何全局学习最多只能把先验拉动 30%**；
- 单用户证据仍保留 30 天 ±0.8 的上限；
- `pos/neg` 继续按时间衰减，但只作用于 `h`，不碰 `p`。

这样即使某个景点被一个团体连续选择，`h` 会涨，但 `s` 不会从 0.15 直接飞到 0.9。

### 2.4 会话/用户偏好

`u_s` 的来源：

- 当前筛选 tag；
- AI 会话 `constraints.interests`；
- 最近 3 次点击/收藏/加站的 tag 衰减和；
- 用户长期 `account_tag_prefs`。

`u_u` 可以存一张小表：

```sql
CREATE TABLE account_tag_affinity (
    account_id BIGINT      NOT NULL,
    tag_id     BIGINT      NOT NULL,
    affinity   DECIMAL(4,3) NOT NULL DEFAULT 0.000,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, tag_id)
);
```

它只用于**该用户的排序**，不进入 `attraction_tag`。

## 3. 反馈到底学什么

### 3.1 从「独立正负计数」改成「pairwise 选择」

当前是「最终站点 +0.8、过程淘汰 -0.2」的独立计数。更合理的是：

```text
P(a ≻ b | u, s) = σ( θ · (φ(a) - φ(b)) )
```

- `a`：用户最终选择/保留的景点；
- `b`：同一会话、同一候选集合里**展示过但没选**的景点；
- `φ`：`priorMatch / learnedMatch / popularity / sessionMatch / quality / distance / novelty / region` 等特征；
- 训练样本只来自真实会话，不用「按 prior 排序造硬负例」。

这直接优化「给定候选，用户选谁」，而不是「这个景点属于哪个 tag」。

### 3.2 负向信号要谨慎

用户删掉一个景点，可能是：

- tag 不匹配；
- 太远/通勤太久；
- 当天时间不够；
- 已经去过；
- 同行人不喜欢；
- 只是换一个顺序。

1.0 的 `ROUTE_STOP_REJECT -0.2` 会把这些原因都摊到 tag 上。2.0 已改为：

- 负向只用于 pairwise 的「未选」标签，不直接写 `neg_i`；
- 如果确实要写 `neg`，只写「用户明确点了『不感兴趣』/『标签不对』」的维度；
- 对「过程淘汰」只作为排序特征 `rejectRisk`，不进入全局 tag 证据。

### 3.3 曝光与位置

没有曝光日志，就无法区分「没选」和「没看到」。2.0 必须补：

```sql
CREATE TABLE tag_impression (
    id          BIGINT AUTO_INCREMENT,
    account_id  BIGINT,
    session_key VARCHAR(64),
    scene       VARCHAR(32),
    poi_id      VARCHAR(32) NOT NULL,
    position    INT,
    model_version VARCHAR(32),
    shown_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_impression_session (session_key, shown_at),
    KEY idx_impression_poi (poi_id, shown_at)
);
```

- 每次推荐/AI 路线返回前，服务端记录实际展示的 top-N（不是传给模型的全部候选）；
- 训练时把 `position` 作为特征，或用 `1/log(1+position)` 做 IPS 权重；
- `ROUTE_STOP_NOT_SELECTED` 只对真实曝光的候选生效。

### 3.4 路线长度与事件粒度

当前每个最终站点一条 `+0.8`，10 站路线总证据 8.0，2 站路线 1.6。建议：

- 会话级事件总权重固定（例如 `1.0`），按站点数/停留时长分摊；
- 或每个站点 `0.8 / sqrt(n_stops)`；
- 同一用户同一天同一路线的重复结算，用确定性幂等键挡住（本轮已做）。

## 4. 数据模型

### 4.1 保留 `attraction_tag` 做先验

`attraction_tag` 继续存 `prior_weight / prior_strength / source`，但**不再被反馈直接写 `pos/neg`**（兼容期可以双写，但服务默认只读先验）。

### 4.2 新增学习层

```sql
CREATE TABLE attraction_tag_learned (
    poi_id          VARCHAR(32) NOT NULL,
    tag_id          BIGINT      NOT NULL,
    pos_count       DECIMAL(10,3) NOT NULL DEFAULT 0.000,
    neg_count       DECIMAL(10,3) NOT NULL DEFAULT 0.000,
    distinct_users  INT           NOT NULL DEFAULT 0,
    last_decay_at   DATETIME      DEFAULT NULL,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (poi_id, tag_id),
    KEY idx_learned_tag (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局学习层，与语义先验分离';
```

- `distinct_users` 用于支持度收缩；
- 反馈处理器只写这张表；
- 回滚只需清空/恢复这张表，不动先验。

### 4.3 事件表补字段

`tag_feedback_event` 建议增加：

| 字段 | 用途 |
|---|---|
| `source` | `CLIENT` / `SERVER`，防止客户端伪造服务端事件 |
| `dim_version` | 解释 `intent_vector` 的维度版本 |
| `model_version` | 排序/模型版本，便于归因 |
| `scene` | `RECOMMEND` / `AI_PLAN` / `PROJECT` |
| `position` | 曝光位置 |
| `exposure_id` | 关联 `tag_impression` |
| `session_id` | 会话级关联（可与 `session_key` 合并） |

本轮已经做了「客户端类型白名单 + 服务端事件内部产生 + 幂等键 + 事务应用」，这些字段是下一步增强。

### 4.4 会话存储

当前已做 Redis 持久化（key `tag:vector:session:*`，TTL 7 天），DB 表不是必须；如果要求强一致/审计，再落 `route_feedback_session` 表。

## 5. 服务模式与灰度

新增 `app.tag-vector.serve-mode`：

| 模式 | 行为 | 用途 |
|---|---|---|
| `PRIOR_ONLY` | 只用 `p`，反馈只入库 | 默认/回滚 |
| `SHADOW` | 线上仍用 `p`，但记录 `h` 和 shadow score | 验证阶段 |
| `BLEND` | `s = (1-w)p + w·h`，`w ≤ 0.3` | 小流量 |
| `PERSONALIZED` | `BLEND` + `u_s/u_u` rerank | 完整版 |

灰度顺序：

1. 先 `PRIOR_ONLY` + 全量曝光日志；
2. `SHADOW` 跑 2 周，离线比较 shadow 和线上排序；
3. `BLEND` 10% 账号，观察 NDCG/CTR/保存率/延迟；
4. `PERSONALIZED` 再 10% → 50% → 100%。

任何阶段都可以一键回 `PRIOR_ONLY`，因为 `p` 没有被改。

## 6. 评估方案

### 6.1 离线

- **时间切分**：用 T0 之前的会话训练，T0 之后的会话测试；不要 `route_id % 5` 随机切。
- **按用户切分**：训练用户和测试用户不重叠，避免用户偏好泄漏。
- **next-choice 口径**：对每个测试会话，候选 = 当时真实曝光的 top-N，label = 用户最终选的景点；算 NDCG@K / Recall@K / MRR@K / AUC。
- **baseline**：`prior-only`、`popularity`、`session-only`、`global-learned`、`blend`、`personalized`。
- **消融**：去掉曝光校正、去掉支持度收缩、去掉 distance、去掉 session 特征，各跑一遍。
- **显著性**：按会话 paired bootstrap，而不是 5 折 t 检验；报告 95% CI。

### 6.2 在线

- shadow 阶段记录「线上顺序」和「shadow 顺序」，用 IPS/DR 估计反事实收益；
- A/B 主指标：推荐 CTR、路线保存率、项目发布/加入率；
- 护栏：P95 延迟（+≤15ms）、零结果率、Top-10 覆盖景点数、维度熵、举报率；
- 每次排序记录 `model_version` + `dim_version`，可回溯。

### 6.3 数据不足时

用模拟用户做可控实验：

1. 给每个模拟用户生成 15 维 latent preference；
2. 按 `softmax(utility)` 从候选里选路线，utility 包含 tag 匹配、距离、热度、噪声；
3. 记录 accept/reject；
4. 比较 no-feedback / global / per-user / blend 的 regret 和 NDCG。

这比用历史路线「自己证明自己」更能说明算法是否真的能学到用户偏好。

## 7. 落地步骤

### Phase 0（本轮已完成）

- 客户端事件白名单、服务端事件内部化、单事件事务、幂等键、过期事件、Redis 会话、TTL、图谱弱边过滤、补标保留 evidence。

### Phase 1（已实现基础版）：可观测

- 事件表补 `source/dim_version/model_version/scene/position/exposure_id`；
- 记录 `tag_impression`；
- 加 `serve-mode=PRIOR_ONLY/SHADOW/BLEND/PERSONALIZED`；
- `TagRankingScorer.shadowScore` 已提供，shadow 日志/看板仍待接。

### Phase 2（已实现核心）：学习层与 pairwise

- 建 `attraction_tag_learned`、`attraction_tag_learned_account`；
- 反馈处理器改为写学习层，按 `distinct_users` 收缩；
- 实现 `s = (1-w)p + w·h` 和 `w` 上限；
- 新增 `TagRankingScorer`、`tag_ranking_model` 与 `TagPairwiseTrainer`（含 80/20 时间切分和 prior/learned/blend 基线指标）；
- AI 规划已接入 classifier 意图 + 归一化 PPR；推荐侧已接入 `TagRankingScorer`；
- 完整的 next-choice 离线评估/消融/在线 shadow 看板仍待补。

### Phase 3（约 1~2 周）：个性化

- `account_tag_affinity` + `u_s/u_u`；
- 会话级 rerank；
- AI 规划接入 classifier/vector（当前仍是 `message.contains(tag)`，且 `getCombinedScore` 的 tag 分固定 1.0）；
- PPR/MMR 口径统一（项目向量用 `route_tag_weight`，PPR 不再 `sum*10` 饱和）。

### Phase 4：灰度

- `BLEND` 10% → 50% → 100%；
- 或先只对「有足够 support」的景点启用学习层；
- 保留 `PRIOR_ONLY` 回滚开关。

## 8. 验收标准

| 指标 | 目标 |
|---|---|
| 离线 NDCG@5（next-choice） | 相对 prior-only +3% |
| pairwise AUC | ≥ 0.60 |
| 曝光覆盖率 | 不低于 baseline |
| P95 延迟 | +≤15ms |
| 线上 CTR / 保存率 | 正向，且护栏不退化 |
| 回滚 | `serve-mode=PRIOR_ONLY` 1 分钟内生效 |

## 9. 不建议做的事

- ❌ 继续把会话最终选择写成全局 `pos/neg`，然后调 `κ` 掩盖过拟合；
- ❌ 用「按 prior 排序取 top 未选」造硬负例，再评估 pairwise；
- ❌ 在没有曝光日志的情况下用「未选」当负例；
- ❌ 让客户端直接发 `ROUTE_STOP_CONFIRM/REJECT` 这类服务端事件；
- ❌ 在没有时间切分/真实会话的情况下宣布「反馈有效」。

## 10. 代码落点

| 模块 | 改动 |
|---|---|
| `TagVectorService` | 增加 `priorVector` / `learnedVector` / `shadowVector` / `serveMode`，`vector()` 按模式合成 |
| `TagLearnedService` | 写 `attraction_tag_learned`，按 `distinct_users` 刷新支持度；与先验分离 |
| `TagFeedbackProcessor` | 默认写学习层；`learned-write-enabled=false` 才回退旧 `pos/neg`；credit 用先验归因 |
| `TagImpressionService` | 记录 `tag_impression`，计算曝光热度快照 |
| `TagRankingModelService` / `TagRankingScorer` | 加载 `tag_ranking_model`；prior/learned/popularity/session/user 融合打分；`shadowScore` 供 shadow |
| `TagPairwiseTrainer` | 从真实曝光 + 会话结算事件训练 pairwise 逻辑回归，写 `tag_ranking_model` |
| `RouteIterationFeedbackService` | 记录 session 级候选、选择、位置；结算后更新 `account_tag_affinity` |
| `KnowledgeGraphRecommendServiceImpl` | 用 `TagRankingScorer` 打分；MMR 项目向量改用 `route_tag_weight`；PPR 归一化 |
| `AiRoutePlanningServiceImpl` | 用 `StudyTourTagClassifier` 生成 intent；`getCombinedScore` 用 `tagScore + normalizedPPR` |
| `eval_feedback` | 时间切分、next-choice、paired bootstrap、baseline/ablation（待补） |

## 11. 一句话总结

**先验负责「这个景点是什么」，学习层负责「用户实际怎么选」，会话层负责「这次想要什么」；三者不要塞进同一个 `attraction_tag.pos/neg`。** 先做 `PRIOR_ONLY + SHADOW`，用时间切分和真实曝光证明学习层有增益，再逐步打开 `BLEND`。
