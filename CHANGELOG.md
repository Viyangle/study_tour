# 更新日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的格式，版本号遵循语义化版本。

## [Unreleased]

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
