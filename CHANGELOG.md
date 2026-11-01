# 更新日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的格式，版本号遵循语义化版本。

## [Unreleased]

### Added

- **模型驱动的路线 Agent**：`AgentRunExecutor` 接入 `AgentLoop`，由模型自主选择 RAG、图谱、地点详情、参考组合、交通查询与路线/交通/时间编辑工具；后端只校验数据合法性，业务可行性由模型判断。新增普通回答 `result.kind=answer`，草稿按任务隔离，不自动写入正式路线。
- **终止型追问与阶段通知**：必要 tag/兴趣、预算等缺失时通过 `ask_user` 追问并立即结束本轮；阶段开始时返回说明文本，同阶段连续调用不重复播报。模型交互及工具调用额度可通过 `AGENT_MAX_MODEL_TURNS`、`AGENT_MAX_TOOL_CALLS` 配置。
- **景点标签 15 维向量（第一版）**：新增 `V4__attraction_tag_vector.sql`（`tag_dimension`、`attraction_tag` 的先验/证据列与 `effective_weight` 生成列、反馈事件表、向量快照表）；新增 `TagVectorService` 统一加载 0~1 权重；`KnowledgeGraphRecommendServiceImpl` 在 `app.tag-vector.enabled=true` 时用 `+50·Σq_i s_i` 对候选打分并放开种子集合，关闭时保持旧逻辑；新增 `GET /tags/interests` 与 `GET /attractions/{poiId}`（详情带 `tagVector`）。
- **景点标签自动打分**：高德同步落库后由 `TagVectorLabelService` 立即打分，LLM（`AttractionTagScorerAi`）优先、超时/失败回退 `StudyTourTagClassifier.score` 规则；历史/脚本数据由启动补标（每批 50）或 ADMIN 接口 `POST /admin/tag-vectors/backfill`、`/{poiId}/relabel` 处理；默认只保留 ≥0.4 的维度（`app.tag-vector.min-prior-weight`），过滤弱相关噪声。
- **离线评估**：`TagVectorEvalTool` + `TagVectorMetrics`，用历史路线站点做 relevance，输出 Recall@10/NDCG@10 与 `eval_out/*.csv`。
- **标签反馈闭环**：新增 `TagFeedbackService`/`TagFeedbackProcessor`/`FeedbackController`；`POST /feedback/events` 接收前端批量事件；处理器每 30 秒按热度公式更新 `pos/neg`，正/负证据按 90/180 天衰减，单用户单景点 30 天上限 ±2.0，显式纠正只作用于指定维度。
- **反馈语义调整**：点赞/点踩、收藏、加入/退出项目、评价改为 `preference-only`，只记录用户偏好，不再修改景点 tag；属性证据只保留显式纠正、路线保存/发布和路线迭代。
- **反馈应用保护**：新增 `app.tag-vector.apply-enabled`，默认 `false`，只采集事件不自动改权重；留出路线实验显示当前正向迭代反馈会让 NDCG@3 从 0.0521 降到 0.0365，验证前不自动应用。
- **路线会话结算**：`RouteIterationFeedbackService` 记录同一 `accountId + memoryId` 过程中出现过的景点，中间轮次不加减分；项目发布时最终路线景点 `ROUTE_STOP_CONFIRM +0.8`，过程出现但未进最终路线 `ROUTE_STOP_REJECT -0.2`。降低幅度为正向的 1/4，符合「只有最终确定的才提高」。
- **候选曝光轻降**：新增 `ROUTE_STOP_NOT_SELECTED -0.02`，记录候选列表展示过、但从未进入任何路线的景点；只对当前 tag 得分 >0 的候选生效。同时排除向量候选池中 0 分景点，避免科技意图把没有科技维度的景点带进 LLM。
- **PPR/MMR 向量化**：`KnowledgeGraph.personalizedPageRank` 支持种子权重与按向量相似度分配同标签边；首页推荐 PPR 按用户偏好向量给种子加权，MMR 用项目站点平均向量的余弦相似度；AI 规划的 PPR 同样按意图匹配度加权。全部由 `app.tag-vector.enabled` 控制，关闭时回退旧的均匀 PPR 与 Jaccard MMR。
- **多标签路线（V5）**：新增 `route_tag_weight(route_id, tag_id, weight)`；路线向量 = 站点景点向量按时长加权平均，保存 AI/手动路线时自动重算，`routes.tag` 回写为 argmax 主标签兼容旧字段；新增 `GET /routes/{id}/tags`，`GET /routes` 列表和 `GET /projects/{id}` 详情返回 `tagVector`。
- **AI 输出 tagVector**：`system-route-compose.txt` 要求模型可选输出 1~3 个主题的 `tagVector`；`AIRoutePlan` 增加 `tagVector`，保存路线时按 `app.tag-vector.route-model-weight`（默认 0.3）与站点聚合向量融合，非法/空向量自动回退单标签。
- **项目列表多标签软筛/软排序**：`GET /projects` 和 `GET /projects/filter` 支持 `tags`、`tagMatch=soft`、`minTagWeight`；软筛按路线向量任一维度达到阈值命中，软排序按所选标签权重和 + 地区分计算，`tagMatch=exact` 仍走原 `projects.tag` 逻辑。

### Fixed

- **领队放弃带队固定返回 500**：`project_abandon_records` 只在 `V1__baseline.sql` 里创建，存量库被 `baseline-on-migrate` 直接标记到 V1 后不会执行 V1，表因此缺失；`POST /projects/{id}/abandon` 写审计记录时抛 SQL 异常、事务整体回滚，接口返回 500「系统繁忙，请稍后再试」。新增幂等迁移 `V3__create_project_abandon_records.sql` 自动补表，`FlywayMigrationTest` 同时校验空库与存量库两条路径都能拿到该表。

## [0.0.4] - 2026-10-27

### Added

- **参数校验**：引入 `spring-boot-starter-validation`，为注册、登录、修改密码、修改角色、加入项目、建群、发消息 7 个请求 DTO 增加约束并在 Controller 启用 `@Valid`；校验失败统一返回 HTTP 400 + `{code:0,msg}`，`msg` 为第一个字段提示。
- **数据库迁移**：接入 Flyway，新增 `V1__baseline.sql`（20 张表基线，去除演示数据、不删表）与 `V2__accounts_role_add_admin.sql`；`baseline-on-migrate` 兼容存量库，已执行脚本受校验和保护。新增 `FlywayMigrationTest` 覆盖空库与存量库两条路径。
- **可观测性**：接入 Actuator（`/actuator/health` 含 liveness/readiness、`/actuator/info`、`/actuator/metrics`）；新增 `TraceIdFilter`，生成或透传 `X-Request-Id` 并写入 MDC，日志格式输出 `[traceId]`。
- **接口契约**：接入 springdoc-openapi，`/v3/api-docs` 从代码生成 OpenAPI 3 契约，`/swagger-ui.html` 在线调试，内置 `bearerAuth` 鉴权配置。
- **测试**：新增 `RequestValidationTest`、`TraceIdFilterTest`、`GlobalExceptionHandlerTest`、`FlywayMigrationTest`；用例数 213 → 270，测试类 29 → 33。
- `KnowledgeGraphRecommendServiceImplTest`：35 个用例覆盖首页推荐打分、冷启动兜底、MMR 多样性、推荐理由生成与 AI 候选召回，核心推荐链路行覆盖率由 0 提升至 99%。
- `docs/architecture.md`、`docs/database.md`、`docs/README.md`：系统分层、部署拓扑、核心链路、ER 图、迁移说明与文档索引。
- `CONTRIBUTING.md`、PR / Issue 模板、Dependabot 配置、`.env.example`、`CHANGELOG.md`。

### Changed

- 数据库、Redis、OSS bucket 改为 `${ENV:默认值}` 形式注入，生产环境不再依赖仓库内的默认口令；新增 `FLYWAY_ENABLED`、`OPENAPI_ENABLED` 开关。
- JaCoCo 覆盖率门禁由行 35% / 分支 27% 上调至行 39% / 分支 31%；实测覆盖率由 36.4% / 28.4% 提升至 40.7% / 33.1%。
- 版本号由 `0.0.3` 升为 `0.0.4`，OpenAPI 与 Actuator info 同步。
- 移除推荐服务中未被调用的 `calcTagMatchScore`：标签匹配实际由 Personalized PageRank 的种子节点体现，保留该方法会让人误读打分维度。
- README 的测试与覆盖率口径、文档链接同步到当前状态。

### Fixed

- `AliOSSUtils` 将 bucket 名称硬编码为 `study-tour-image`，导致 `aliyun.oss.bucketName` 配置不生效；现已改为读取配置项，并将配置值校正为实际使用的 bucket。
- `accounts.role` 的 ENUM 缺少 `ADMIN`，与代码支持的管理员角色不一致，设置管理员时会写库失败；已由 Flyway `V2` 修复，`full_schema.sql` 同步。
- 请求体 JSON 解析失败时不再把 Jackson 内部异常文本返回给客户端，统一为「请求体格式不正确」。
- `/upload` 增加空文件校验，并在 OpenAPI 中正确标注为 `multipart/form-data`。
- README 顶部指向已删除文件 `BACKEND_TODO.md` 的失效链接。

## [0.0.3] - 2026-10-19

### Added

- 异步路线规划 Agent 接口（`POST/GET/DELETE /routes/agent/runs`）：任务视图、阶段事件、幂等提交、会话互斥、限流与取消。
- 领队放弃带队审计：`POST /projects/{id}/abandon` 增加 6 小时时间窗与原因校验，新增 `project_abandon_records` 表与查询接口。
- `GET /projects/mine` 支持 `stage` / `statuses` 阶段筛选，非法枚举返回 400。
- 路线收藏、项目群聊生命周期、知识图谱语义边等能力。
- GitHub Actions CI、JaCoCo 覆盖率门禁、Postman 集合与压测脚本。

### Changed

- 权限与数据隔离收口：评价作者取自登录身份、AI 会话按账号隔离、账号禁用/角色变更在下一次认证生效。
- 项目列表与详情返回 `availabilityStatus`、`viewerRole`、操作权限与 `groupId`，减少前端二次请求。

### Fixed

- 修复语义边合并后的联调问题，补齐放弃带队、阶段筛选等边界用例。
