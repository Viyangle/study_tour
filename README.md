# Study Tour 后端

> 研学路线规划与组队平台后端：知识图谱推荐、AI 路线规划 Agent、项目组队与群聊。本文档同时是完整的接口说明，设计文档见 [docs/](docs/README.md)。

权限收尾与后续计划见 [docs/README.md](docs/README.md)（架构、数据库、接口与变更记录索引）。账号响应不返回 `passwordHash`；受保护接口使用 access token，refresh token 仅用于 `/login/refresh`。账号禁用或角色变更在下一次认证时生效。

## 0. 测试与质量

[![CI](https://github.com/Viyangle/study_tour/actions/workflows/ci.yml/badge.svg)](https://github.com/Viyangle/study_tour/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)
![Tests](https://img.shields.io/badge/tests-270%20passed-success)
![Coverage](https://img.shields.io/badge/coverage-40.7%25-yellow)

| 项目 | 现状 |
|---|---|
| 用例规模 | 270 个用例、0 失败；分布于 33 个测试类（另 1 个需本地 MySQL/Redis 的冒烟测试），JUnit 5 + Mockito + MockMvc + AssertJ |
| CI | GitHub Actions，`mvn -B -ntp -Pci verify`，push 与 PR 触发 |
| 覆盖率 | JaCoCo 行 40.7% / 分支 33.1%，报告随构建产出并上传为 artifact |
| 覆盖率门禁 | 行 ≥ 39%、分支 ≥ 31%，低于阈值直接构建失败（棘轮阈值，防止回退） |
| 参数校验 | Jakarta Bean Validation + `@Valid`，覆盖注册、登录、改密、改角色、加入项目、建群、发消息；失败统一返回 400 + `{code:0,msg}` |
| 数据库迁移 | Flyway：`db/migration` 下的 V1 基线 + 增量脚本，启动时自动执行；存量库自动 baseline，不重复建表 |
| 可观测性 | `/actuator/health`、`/actuator/info`、`/actuator/metrics`；每个请求生成 `X-Request-Id` 并写入日志 MDC |
| 接口契约 | springdoc 从代码生成 OpenAPI 3：`/v3/api-docs`、`/swagger-ui.html` |

工程约定见 [CONTRIBUTING.md](CONTRIBUTING.md)，版本变更见 [CHANGELOG.md](CHANGELOG.md)，架构与数据库设计见 [docs/](docs/README.md)。配置项通过环境变量注入，清单见 [.env.example](.env.example)。

### 本地运行

```bash
mvn -B -ntp -Pci verify     # 与 CI 一致：排除需要外部服务的用例
mvn -B -ntp test            # 本机已启动 MySQL / Redis 时执行全量
mvn -B -ntp test -Dtest=FlywayMigrationTest   # 只验证数据库迁移（需要本机 MySQL）
```

- 接口文档：`https://localhost:8443/swagger-ui.html`（关闭 HTTPS 时用 `http://localhost:8080/swagger-ui.html`）
- 健康检查：`/actuator/health`；指标：`/actuator/metrics`（需登录）
- 覆盖率报告：`target/site/jacoco/index.html`
- 测试报告：`target/surefire-reports/`

### 数据库迁移

应用启动时由 Flyway 执行 `src/main/resources/db/migration` 下的脚本：

- 空库：`V1__baseline.sql` 建 20 张表，再执行后续增量（当前为 `V2__accounts_role_add_admin.sql`）。
- 存量库：`baseline-on-migrate` 把 V1 记为已执行，只执行 V2 及之后的增量。
- 已执行过的脚本不要修改（校验和不匹配会导致启动失败），新变更请新增 `V<版本>__<描述>.sql`。

### 测试分层与约定

- **单元测试**：Service、Agent、Mapper XML 解析等，用 `@Mock` 替身隔离 MySQL、Redis 与外部 HTTP 依赖，无需任何外部服务即可运行。
- **接口与安全集成测试**：`controller` / `filter` / `security` 包下基于 MockMvc + Spring Security 过滤器链，同时校验状态码、响应体与响应头，覆盖鉴权、越权、账号禁用等失败流。
- **校验与链路测试**：`validation` 包校验 DTO 约束，`filter` 包校验 traceId 生成/透传/清理，`exception` 包锁定统一错误体。
- **迁移集成测试**：`FlywayMigrationTest` 在真实 MySQL 上验证空库建表与存量库增量，标记 `@Tag("requires-infra")`，CI 排除。
- **上下文冒烟测试**：`StudyTourApplicationTests` 标记 `@Tag("requires-infra")`，需要本机 MySQL（3306）与 Redis（6379）；CI 通过 `-Pci` 排除该分组。
- **性能基线**：`perf/` 保存压测脚本与多轮次结果 CSV，用于版本间性能回归对比。

未覆盖的主要是 `utils/` 下的一次性数据批处理工具（高德数据导入、Excel 解析、SQL 生成等），它们以 `main()` 驱动、不参与请求链路，后续可按需补测。

## 1. 基础信息

- 项目名称：`study_tour`
- 技术栈：`Spring Boot 3 + MyBatis + MySQL + Redis`
- 默认服务地址：`http://47.94.95.110:8080` (已重定向为https服务)
- 连接测试：`GET /login/ping`
- 数据格式：`application/json`

## 2. 统一返回格式

所有已实现接口统一返回 `Result`：

```json
{
  "code": 1,
  "msg": "success",
  "data": {}
}
```

例外：路线规划 Agent 接口 `/routes/agent/runs` 与 `/health` 按前端约定直接返回 JSON 对象，
不包裹 `Result`；错误体为 `{"error": "..."}`，参数校验失败为 HTTP 422 + `{"detail": [...]}`。
详见 [4.3.8](#438-路线规划-agent-接口异步任务)。

#### 4.6.4 同步单个高德 POI（一条龙）

- 方法：`POST`
- 路径：`/attractions/sync/{poiId}`
- 描述：按高德 poiId 同步单个景点：高德 place/detail 取数 → 写入 `attractions` 表（upsert）→ 增量更新 Redis 向量索引。适合前端从高德选点后立即调用，让该景点马上进入后端景点库和向量检索候选。
- 前提：Redis 向量索引增量更新需要 Redis Stack（RediSearch），并开启 `app.rag.embedding.store-enabled=true`；向量索引更新失败不会回滚 MySQL 登记结果

请求示例：

```http
POST /attractions/sync/B00190BBCZ
```

响应中的 `data` 是同步后的景点对象：

```json
{
  "code": 1,
  "msg": "success",
  "data": {
    "poiId": "B00190BBCZ",
    "name": "玄武湖景区",
    "location": "118.812688,32.069455",
    "adcode": "320102",
    "citycode": "025",
    "status": "ACTIVE"
  }
}
```

#### 4.6.5 定期同步与向量索引重建

- 数据库定期更新仍使用现有脚本手动执行：`scripts/sync_attractions_top50_from_json.ps1`（或 `AmapAttractionBatchExporter --syncDb=true`）从高德拉取数据写入 MySQL。
- 全量重建 Redis 向量索引有两种方式：
  1. 启动时自动重建：开启 `app.rag.embedding.ingest-enabled=true`（可选同时开启 `clear-before-ingest=true`），应用启动时按数据库当前有效景点重建向量索引；
  2. 调用管理接口 `POST /attractions/reindex`（需 ADMIN 角色），内部先清空索引再按数据库全量写入。
- 前提：Redis 使用 Redis Stack（RediSearch），并开启 `app.rag.embedding.store-enabled=true`。

字段说明：

- `code`：`1` 表示成功，`0` 表示失败
- `msg`：返回消息
- `data`：返回数据，新增/修改类接口通常为 `null`

失败示例：

```json
{
  "code": 0,
  "msg": "用户名或密码错误",
  "data": null
}
```

## 3. 数据模型

### 3.1 Account

```json
{
  "id": 1,
  "role": "LEADER",
  "username": "Leader",
  "phone": "10010001001",
  "regionCode": "210000",
  "avatarUrl": null,
  "intro": null,
  "status": 1,
  "createdAt": "2026-03-12T21:00:00",
  "updatedAt": "2026-03-12T21:00:00"
}
```

### 3.2 LeaderProfile

```json
{
  "accountId": 3,
  "intro": "多年研学带队经验",
  "totalRating": 95,
  "ratingCount": 20
}
```

### 3.3 AccountTagPref

```json
{
  "accountId": 1,
  "tagId": 2
}
```
| tagId | tagName | 分类 |
|------|---------|------|
| 1 | 历史人文 | INTEREST |
| 2 | 博物馆研学 | INTEREST |
| 3 | 非遗体验 | INTEREST |
| 4 | 科技探索 | INTEREST |
| 5 | 自然生态 | INTEREST |
| 6 | 地理地质 | INTEREST |
| 7 | 航天航空 | INTEREST |
| 8 | 农耕劳动 | INTEREST |
| 9 | 艺术美育 | INTEREST |
| 10 | 红色教育 | INTEREST |
| 11 | 高校参访 | INTEREST |
| 12 | 职业启蒙 | INTEREST |
| 13 | 英语实践 | INTEREST |
| 14 | 摄影记录 | INTEREST |
| 15 | 亲子互动 | INTEREST |
| 16 | 徒步拉练 | ROUTE_STYLE |
| 17 | 骑行观光 | ROUTE_STYLE |
| 18 | 公交串联 | ROUTE_STYLE |
| 19 | 地铁打卡 | ROUTE_STYLE |
| 20 | 自驾漫游 | ROUTE_STYLE |
| 21 | 水上游览 | ROUTE_STYLE |
| 22 | 夜游专场 | ROUTE_STYLE |
| 23 | 一日速览 | ROUTE_STYLE |
| 24 | 多日慢游 | ROUTE_STYLE |
| 25 | 定制私享 | ROUTE_STYLE |
| 26 | 亲子家庭 | CROWD |
| 27 | 学生团体 | CROWD |
| 28 | 情侣出游 | CROWD |
| 29 | 银发长者 | CROWD |
| 30 | 企业团建 | CROWD |
| 31 | 朋友结伴 | CROWD |
| 32 | 单人独行 | CROWD |
| 33 | 师生研学 | CROWD |
| 34 | 外宾接待 | CROWD |
| 35 | 无障碍友好 | CROWD |
| 36 | 世界遗产 | SCENIC |
| 37 | 5A景区 | SCENIC |
| 38 | 4A景区 | SCENIC |
| 39 | 红色基地 | SCENIC |
| 40 | 非遗工坊 | SCENIC |
| 41 | 特色小镇 | SCENIC |
| 42 | 古村落 | SCENIC |
| 43 | 主题公园 | SCENIC |
| 44 | 动植物园 | SCENIC |
| 45 | 科技馆/博物馆 | SCENIC |

### 3.4 Attraction

```json
{
  "id": 1,
  "name": "故宫博物院",
  "type": "HISTORY",
  "location": "北京市东城区景山前街4号",
  "regionCode": "110101",
  "description": "历史文化研学景点",
  "recommendedDuration": 180,
  "createdAt": "2026-03-10T10:00:00",
  "updatedAt": "2026-03-10T10:00:00"
}
```

### 3.5 RouteAttraction

```json
{
  "id": 1,
  "routeId": 2,
  "attractionId": 1,
  "visitOrder": 1,
  "visitTime": "08:00:00",
  "recommendedDuration": 180,
  "notes": "上午优先参观",
  "createdAt": "2026-03-12T21:00:00"
}
```

### 3.6 Project

```json
{
  "id": 1,
  "routeId": 2,
  "ownerAccountId": 1,
  "leaderAccountId": 3,
  "title": "北京历史文化研学",
  "departureDate": "2026-03-12",
  "departureTime": "08:30:00",
  "startPointType": "MANUAL",
  "startPoint": "北京南站北广场",
  "leaderRequirements": "有博物馆研学带队经验",
  "participantRequirements": "请准时集合",
  "maxMembers": null,
  "currentMembers": 10,
  "status": "OPEN",
  "createdAt": "2026-03-12T21:00:00",
  "updatedAt": "2026-03-12T21:00:00"
}
```

### 3.7 ProjectMember

```json
{
  "id": 1,
  "projectId": 1,
  "accountId": 2,
  "joinStatus": "JOINED",
  "representedCount": 3,
  "joinedAt": "2026-03-12T21:00:00"
}
```

## 领队端页面接口（前端对照）

领队端仓库当前没有网络层，以下接口按页面交互提供，可直接作为 Retrofit 接口定义。所有接口都需要 `Authorization: Bearer <token>`；`LEADER` 和 `BOTH` 角色可访问，账号 ID 均从 JWT 获取。

### 可接项目与项目详情

领队端与普通用户端统一使用项目接口，不再维护独立订单模型：

- `GET /projects/available`：领队可接项目分页列表
- `GET /projects/{projectId}`：项目聚合详情
- `GET /projects/mine`：与当前账号有关的项目

响应直接使用 `Project`，并附带 `availabilityStatus`、`viewerRole`、操作权限和
`groupId`。

### 接单

- 方法：`POST`
- 路径：`/projects/{projectId}/accept`
- 请求体：无
- 描述：后端使用数据库行锁保证同一项目只能被一个领队接到；已过出发时间、已有领队或状态不可转换时返回失败。

```http
POST /projects/20/accept
Authorization: Bearer <token>
```

### 放弃带队

- 方法：`POST`
- 路径：`/projects/{projectId}/abandon`
- 参数：`reason` 必填，去除首尾空格后 5–200 字；查询串 `?reason=xxx`、JSON 请求体 `{"reason":"xxx"}`、表单参数 `reason=xxx` 三种传法都支持
- 描述：仅当前接单领队可操作。项目必须处于 `CONFIRMED`，且距出发时间至少 6 小时（恰好 6 小时允许）；成功后清空领队并将项目恢复为 `OPEN`，其他领队可重新接单，同时写入一条放弃审计记录。
- 失败：原因缺失 / 空白 / 少于 5 字 / 超过 200 字、出发时间缺失 → `400`；距出发不足 6 小时、非当前领队、状态不是 `CONFIRMED` → `403`

```http
POST /projects/20/abandon?reason=临时家里有事，无法带队
Authorization: Bearer <leader-token>
```

放弃记录可用 `GET /projects/20/abandon-records` 查询（项目发布者、当前或曾放弃的领队、`ADMIN` 可查看）。

### 我的资料看板

- 方法：`GET`
- 路径：`/leader/profile`
- 描述：一次返回头像、姓名、领队简介、真实评价统计、接单统计、偏好标签和最近三条评价。

```json
{
  "code": 1,
  "msg": "success",
  "data": {
    "accountId": 80,
    "username": "王若彤",
    "avatarUrl": null,
    "regionCode": "320100",
    "intro": "南京研学领队",
    "averageRating": 4.75,
    "ratingCount": 12,
    "acceptedProjectCount": 18,
    "completedProjectCount": 15,
    "tagNames": ["历史人文", "博物馆研学"],
    "recentReviews": []
  }
}
```

头像更换继续使用 `POST /upload` 上传后，再调用 `PUT /accounts/{id}` 提交 `avatarUrl`；领队简介继续使用 `POST /accounts/{id}/intro`。

### 领队评价列表

- 方法：`GET`
- 路径：`/leader/reviews`
- 参数：`pageNum`（默认 `1`）、`pageSize`（默认 `20`，最大 `100`）
- 描述：只返回 `USER_TO_LEADER` 类型评价，并附带评价人姓名和头像，按时间倒序排列。

```json
{
  "id": 3,
  "projectId": 20,
  "routeId": 38,
  "reviewerAccountId": 86,
  "reviewerName": "刘佳琪",
  "reviewerAvatarUrl": null,
  "overallScore": 5,
  "content": "讲解很专业",
  "createdAt": "2026-07-20T18:30:00"
}
```

### 消息页复用接口

消息页继续使用 `GET /chat/sessions`、`GET /chat/sessions/{sessionId}/messages`、`GET /chat/sessions/{sessionId}/members` 和 `POST /chat/messages`。会话列表项额外返回：

- `projectTitle`：项目群聊标题
- `latestMessage`：最后一条消息内容
- `latestMessageAt`：最后消息时间
- `latestMessageSenderAccountId`：最后消息发送人

前端仓库中的 `txtPrice`、路线图片和评价标签目前只有布局占位，没有对应请求字段或业务数据模型，因此本次没有虚构价格、图片或评价标签接口。

`currentMembers` 是所有 `JOINED/COMPLETED` 成员的 `representedCount` 之和，不是账号数量；`maxMembers=null` 表示项目不设总人数上限。

## 项目聚合接口

以下接口均从 JWT 获取当前账号，不再要求前端传 `accountId`。

### 可接项目

```http
GET /projects/available?pageNum=1&pageSize=10
Authorization: Bearer <leader-token>
```

仅 `LEADER/BOTH` 可访问，返回未来、无领队且状态为 `OPEN/MATCHING` 的项目。

### 我的项目

```http
GET /projects/mine?relation=ALL&status=OPEN&pageNum=1&pageSize=10
Authorization: Bearer <token>
```

`relation` 支持：

- `ALL`：与当前账号有关的全部项目
- `PUBLISHED`：我发布的项目
- `LEADING`：我作为领队的项目
- `JOINED`：我作为参与者加入的项目

状态与阶段筛选（可组合，非法值返回 `400`，不再静默忽略）：

- `status`：单个原始状态，如 `OPEN`、`CONFIRMED`、`DONE`
- `statuses`：多个原始状态，重复传参即可（`?statuses=CONFIRMED&statuses=IN_PROGRESS`）
- `stage`：阶段筛选，`ACCEPTED` → `CONFIRMED/IN_PROGRESS`，`COMPLETED` → `DONE`
- 优先级：`stage` > `statuses` > `status`

统一分页响应位于 `data`：

```json
{
  "items": [],
  "total": 10,
  "pageNum": 1,
  "pageSize": 10,
  "pages": 1
}
```

每个 `Project` 额外包含 `publisherName`、`leaderName`、`availabilityStatus`、
`viewerRole`、`canAccept`、`canJoin`、`canManageGroup` 和 `groupId`，前端可直接据此
展示项目信息、控制按钮和区分 `PUBLISHER/LEADER/PARTICIPANT` 身份。

### 项目详情

```http
GET /projects/{projectId}
Authorization: Bearer <token>
```

## 4. 接口列表

以下接口按业务分组整理，便于联调与测试。每个接口均预留了“响应结果（待补充）”区域，方便你后续补齐真实返回示例。

### 4.1 用户相关

#### 4.1.1 用户注册

- 方法：`POST`
- 路径：`/register`
- 描述：公开注册保留用户自行选择角色的能力，可选择 `USER/LEADER/BOTH`。`role` 必填，不区分大小写，服务端会归一化为大写；公开注册不允许使用 `ADMIN`，不会再强制注册为 `USER`。

请求示例：

```json
{
  "username": "B",
  "phone": "10010001003",
  "password": "123456",
  "confirmPassword": "123456",
  "role": "USER",
  "regionCode": "320102"
}
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": null
}
```

#### 4.1.2 用户登录

- 方法：`POST`
- 路径：`/login`
- 描述：按手机号和密码登录，返回账号信息与双 Token （目前暂时不需要）

请求示例：

```json
{
  "phone": "19910001003",
  "password": "123456"
}
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": {
        "account": {
            "id": 6,
            "role": "BOTH",
            "username": "B",
            "phone": "10010001003",
            "regionCode": "320102",
            "avatarUrl": null,
            "intro": "喜欢把城市历史、博物馆展览和实地观察结合起来，偏好节奏清晰的研学路线。",
            "status": 1,
            "createdAt": "2026-03-30T16:37:02",
            "updatedAt": "2026-03-30T16:37:02"
        },
        "token": "<access-token>",
        "refreshToken": "<refresh-token>"
    }
}
```
### JWT令牌说明

请求头需携带 `Authorization: Bearer <token>`

- token: 登录（有效期24小时，推荐打开APP时自动更新）
- refreshToken: 更新令牌（有效期30天，之后需重新登录）

#### 4.1.3 刷新登录 Token

- 方法：`POST`
- 路径：`/login/refresh`
- 描述：使用 `refreshToken` 刷新访问令牌

请求示例：

```http
POST /login/refresh?refreshToken=xxxxx
```

响应结果：

```json

```

#### 4.1.4 服务连通性检查

- 方法：`GET`
- 路径：`/login/ping`
- 描述：连通性检查

请求示例：

```http
GET /login/ping
```

响应结果：

```text
ok
```

#### 4.1.5 获取用户详情

- 鉴权：4.1.5 ~ 4.1.14 接口均需请求头携带 `Authorization: Bearer <token>`

- 方法：`GET`
- 路径：`/accounts/{id}`
- 描述：根据用户 ID 获取用户信息

请求示例：

```http
GET /accounts/6
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": {
        "id": 6,
        "role": "BOTH",
        "username": "B",
        "phone": "10010001003",
        "regionCode": "320102",
        "avatarUrl": null,
        "intro": "喜欢把城市历史、博物馆展览和实地观察结合起来，偏好节奏清晰的研学路线。",
        "status": 1,
        "createdAt": "2026-03-30T16:37:02",
        "updatedAt": "2026-03-30T16:37:02"
    }
}
```

#### 4.1.6 修改个人信息

- 方法：`PUT`
- 路径：`/accounts/{id}`
- 权限：仅本人可改；`ADMIN` 可改任意用户
- 描述：修改账号基础资料。手机号 `phone` 暂不支持修改，若请求体包含 `phone` 应返回错误。角色不通过该接口修改，请使用 `PUT /accounts/{id}/role`。密码不通过该接口修改，请使用 `PUT /accounts/{id}/password`。普通用户简介不通过该接口修改，请使用 `PUT /accounts/{id}/userIntro`。

请求示例：

```json
{
  "username": "B同学",
  "regionCode": "320100",
  "avatarUrl": "https://study-tour-image.oss-cn-beijing.aliyuncs.com/avatar.png"
}
```

请求字段：

- `username`：用户名（可选）
- `regionCode`：所在地区编码（可选）
- `avatarUrl`：头像 URL（可选，通常先调用 `/upload` 获取）
- `status`：账号状态（可选；本人可提交自己的状态，`ADMIN` 可提交任意用户的状态）

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": {
        "id": 6,
        "role": "BOTH",
        "username": "B同学",
        "phone": "10010001003",
        "regionCode": "320100",
        "avatarUrl": "https://study-tour-image.oss-cn-beijing.aliyuncs.com/avatar.png",
        "intro": "喜欢把城市历史、博物馆展览和实地观察结合起来，偏好节奏清晰的研学路线。",
        "status": 1,
        "createdAt": "2026-03-30T16:37:02",
        "updatedAt": "2026-05-31T20:10:00"
    }
}
```

失败示例（尝试修改手机号）：

```json
{
    "code": 0,
    "msg": "手机号暂不支持修改",
    "data": null
}
```

失败示例（尝试在个人信息接口修改角色）：

```json
{
    "code": 0,
    "msg": "角色请使用/accounts/{id}/role接口修改",
    "data": null
}
```

失败示例（尝试在个人信息接口修改简介）：

```json
{
    "code": 0,
    "msg": "普通用户简介请使用/accounts/{id}/userIntro接口修改",
    "data": null
}
```

#### 4.1.7 修改用户角色

- 方法：`PUT`
- 路径：`/accounts/{id}/role`
- 权限：本人可调用并切换自己的 `USER/LEADER/BOTH` 角色；`ADMIN` 可管理任意用户，并可设置 `USER/LEADER/BOTH/ADMIN`。普通用户不能将自己或他人设置为 `ADMIN`。
- 描述：单独修改账号角色。若修改为 `LEADER` 或 `BOTH`，后端会自动补齐领队资料记录。修改当前登录用户自己的角色时，响应会返回新的 `token` 和 `refreshToken`，前端应替换本地旧 token。

请求示例：

```json
{
  "role": "LEADER"
}
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": {
        "account": {
            "id": 6,
            "role": "LEADER",
            "username": "B同学",
            "phone": "10010001003",
            "regionCode": "320100",
            "avatarUrl": "https://study-tour-image.oss-cn-beijing.aliyuncs.com/avatar.png",
            "intro": "喜欢把城市历史、博物馆展览和实地观察结合起来，偏好节奏清晰的研学路线。",
            "status": 1,
            "createdAt": "2026-03-30T16:37:02",
            "updatedAt": "2026-05-31T20:15:00"
        },
        "token": "new-access-token",
        "refreshToken": "new-refresh-token"
    }
}
```

#### 4.1.8 修改密码

- 方法：`PUT`
- 路径：`/accounts/{id}/password`
- 权限：仅本人可改；`ADMIN` 可重置任意用户密码。本人修改密码必须提供 `oldPassword`，管理员重置他人密码时可不传 `oldPassword`。
- 描述：修改登录密码。新密码会使用 BCrypt 加密后保存。

请求示例：

```json
{
  "oldPassword": "123456",
  "newPassword": "654321",
  "confirmPassword": "654321"
}
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": null
}
```

失败示例：

```json
{
    "code": 0,
    "msg": "旧密码错误",
    "data": null
}
```

#### 4.1.9 修改普通用户简介

- 方法：`PUT`
- 路径：`/accounts/{id}/userIntro`
- 权限：`USER`，且仅本人可改；`ADMIN` 可改任意普通用户。`BOTH` 账号可修改普通用户简介；纯 `LEADER` 账号的领队简介仍使用 `/accounts/{id}/intro`。
- 描述：修改 `accounts.intro` 中的普通用户简介，长度不超过 500 个字符。

请求示例：

```json
{
  "intro": "喜欢把城市历史、博物馆展览和实地观察结合起来，偏好节奏清晰的研学路线。"
}
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": {
        "id": 6,
        "role": "BOTH",
        "username": "B同学",
        "phone": "10010001003",
        "regionCode": "320100",
        "avatarUrl": "https://study-tour-image.oss-cn-beijing.aliyuncs.com/avatar.png",
        "intro": "喜欢把城市历史、博物馆展览和实地观察结合起来，偏好节奏清晰的研学路线。",
        "status": 1,
        "createdAt": "2026-03-30T16:37:02",
        "updatedAt": "2026-05-31T20:20:00"
    }
}
```

#### 4.1.10 获取用户标签偏好

- 方法：`GET`
- 路径：`/accounts/{id}/tagPrefs`
- 描述：获取指定用户标签偏好列表

请求示例：

```http
GET /accounts/6/tagPrefs
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": [
        {
            "accountId": 6,
            "tagId": 1
        },
        {
            "accountId": 6,
            "tagId": 2
        }
    ]
}
```

#### 4.1.11 修改用户标签偏好

- 方法：`POST`
- 路径：`/accounts/{id}/tagPrefs`
- 权限：`USER/LEADER`，且仅本人可改；`ADMIN` 可改任意用户
- 描述：覆盖式更新用户标签偏好

请求示例：

```json
[
  {
    "accountId": 6,
    "tagId": 1
  },
  {
    "accountId": 6,
    "tagId": 2
  }
]
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": null
}
```

#### 4.1.12 获取领队资料

- 方法：`GET`
- 路径：`/accounts/{id}/leaderProfile`
- 描述：获取领队资料

请求示例：

```http
GET /accounts/3/leaderProfile
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": {
        "accountId": 3,
        "intro": "test",
        "totalRating": null,
        "ratingCount": null
    }
}
```

#### 4.1.13 修改领队简介

- 方法：`POST`
- 路径：`/accounts/{id}/intro`
- 权限：`LEADER`，且仅本人可改；`ADMIN` 可改任意用户
- 描述：更新领队简介

请求示例：

```json
{
  "intro": "多年研学带队经验"
}
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": null
}
```

#### 4.1.14 文件上传（OSS）

- 方法：`POST`
- 路径：`/upload`
- `Content-Type`：`multipart/form-data`
- 描述：上传图片文件，返回可访问 URL

请求示例（curl）：

```bash
curl -X POST "http://10.6.86.86/upload" \
  -F "image=@D:/tmp/avatar.png"
```

响应结果：

```json
{
  "code": 1,
  "msg": "success",
  "data": "https://study-tour-image.oss-cn-beijing.aliyuncs.com/28afeeb9-afa8-4e16-99e2-4f8aac53a5c3.jpg"
}
```

### 4.2 项目相关

#### 4.2.1 获取项目列表

- 方法：`GET`
- 路径：`/projects`
- 描述：按用户偏好分页获取项目列表，支持返回推荐理由

请求示例（普通分页）：

```http
GET /projects?accountId=6&pageNum=1&pageSize=10
```

请求示例（按关键字搜索）：

```http
GET /projects?accountId=6&keyword=南京&pageNum=1&pageSize=10
```

请求示例（带推荐理由）：

```http
GET /projects?accountId=6&pageNum=1&pageSize=10&withExplanation=true
```

请求参数：

- `accountId`：用户 ID（必填）
- `pageNum`：页码（可选，默认 `1`， 起始为`1`）
- `pageSize`：每页数量（可选，默认 `10`）
- `keyword`：项目关键字（可选，不区分英文大小写，模糊匹配标题、标签、状态、出发点、领队要求和参与者要求）
- `withExplanation`：是否返回推荐理由（可选，默认 `false`；传 `true` 时返回推荐分数和理由）

响应结果：（好吧目前没有太多project）

```json
{
    "code": 1,
    "msg": "success",
    "data": [
        {
            "id": 6,
            "routeId": 2,
            "regionAdcode": "320102",
            "tag": "历史人文",
            "ownerAccountId": 1,
            "leaderAccountId": null,
            "title": "test",
            "departureDate": "2026-03-12",
            "maxMembers": 3,
            "currentMembers": 1,
            "status": "OPEN",
            "createdAt": "2026-03-28T16:54:55",
            "updatedAt": "2026-03-28T16:54:55"
        },
        {
            "id": 1,
            "routeId": 2,
            "regionAdcode": null,
            "tag": null,
            "ownerAccountId": 1,
            "leaderAccountId": 3,
            "title": "test",
            "departureDate": "2026-03-12",
            "maxMembers": 3,
            "currentMembers": 1,
            "status": "OPEN",
            "createdAt": "2026-03-12T16:38:12",
            "updatedAt": "2026-03-12T19:56:33"
        }
    ]
}
```

#### 4.2.2 复合筛选项目

- 方法：`GET`
- 路径：`/projects/filter`
- 描述：按多个条件复合筛选项目。不传筛选参数时，返回未筛选列表，并按 `accountId` 对应账号的地区和标签偏好排序；传入 `regionCode` 或 `tag` 时，会覆盖账号信息中的地区和标签偏好，同时作为筛选条件。

请求示例（未筛选，仅按账号偏好排序）：

```http
GET /projects/filter?accountId=6&pageNum=1&pageSize=10
```

请求示例（复合筛选）：

```http
GET /projects/filter?accountId=6&keyword=南京&regionCode=320100&tag=博物馆研学&status=OPEN&departureDateFrom=2026-05-01&departureDateTo=2026-06-30&onlyAvailable=true&pageNum=1&pageSize=10
```

请求参数：

- `accountId`：用户 ID（可选，用于默认排序）
- `pageNum`：页码（可选，默认 `1`，起始为 `1`）
- `pageSize`：每页数量（可选，默认 `10`）
- `keyword`：关键字（可选，不区分英文大小写，模糊匹配标题、标签、状态、出发点、领队要求和参与者要求）
- `regionCode`：地区编码（可选，精确匹配或同城市前四位匹配；传入后覆盖账号地区）
- `tag`：研学标签（可选，精确匹配；传入后覆盖账号标签偏好）
- `status`：项目状态（可选，`OPEN/MATCHING/CONFIRMED/IN_PROGRESS/DONE/CANCELLED`）
- `departureDateFrom`：出发日期起始值（可选，格式 `yyyy-MM-dd`）
- `departureDateTo`：出发日期结束值（可选，格式 `yyyy-MM-dd`）
- `ownerAccountId`：项目创建者账号 ID（可选）
- `leaderAccountId`：领队账号 ID（可选）
- `hasLeader`：是否已有领队（可选，`true/false`）
- `onlyAvailable`：是否只看可报名项目（可选，`true` 时要求 `OPEN/MATCHING` 且未满员）

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": [
        {
            "id": 10,
            "routeId": 38,
            "regionAdcode": "320100",
            "tag": "博物馆研学",
            "ownerAccountId": 86,
            "leaderAccountId": 80,
            "title": "南京博物院深度讲解研学团",
            "departureDate": "2026-05-09",
            "maxMembers": 21,
            "currentMembers": 3,
            "status": "OPEN",
            "createdAt": "2026-05-05T15:34:07",
            "updatedAt": "2026-05-15T14:52:02"
        }
    ]
}
```

#### 4.2.3 获取项目详情

- 方法：`GET`
- 路径：`/projects/{id}`
- 描述：根据项目 ID 获取项目详情

请求示例：

```http
GET /projects/1
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": {
        "id": 1,
        "routeId": 2,
        "regionAdcode": null,
        "tag": null,
        "ownerAccountId": 1,
        "leaderAccountId": 3,
        "title": "test",
        "departureDate": "2026-03-12",
        "maxMembers": 3,
        "currentMembers": 1,
        "status": "OPEN",
        "createdAt": "2026-03-12T16:38:12",
        "updatedAt": "2026-03-12T19:56:33"
    }
}
```

#### 4.2.4 获取项目成员

- 方法：`GET`
- 路径：`/projects/{id}/members`
- 描述：获取项目成员列表

请求示例：

```http
GET /projects/1/members
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": [
        {
            "id": 1,
            "projectId": 1,
            "accountId": 1,
            "joinStatus": "JOINED",
            "representedCount": 3,
            "joinedAt": "2026-03-12T16:38:12"
        },
        {
            "id": 2,
            "projectId": 1,
            "accountId": 2,
            "joinStatus": "JOINED",
            "representedCount": 2,
            "joinedAt": "2026-03-12T19:18:53"
        }
    ]
}
```

#### 4.2.5 创建项目

- 方法：`POST`
- 路径：`/projects`
- 权限：`USER`（`BOTH` 继承该权限）；`ownerAccountId` 由当前登录用户决定（前端传入会被覆盖）
- 描述：兼容的项目创建接口；新前端优先使用 `/routes/{routeId}/publish`

请求示例：

```json
{
  "routeId": 2,
  "leaderAccountId": 3,
  "title": "北京历史文化研学",
  "departureDate": "2026-03-12",
  "departureTime": "08:30:00",
  "startPointType": "MANUAL",
  "startPoint": "北京南站北广场",
  "leaderRequirements": "有博物馆研学经验",
  "participantRequirements": "请勿迟到",
  "representedCount": 3,
  "maxMembers": null,
  "status": "OPEN"
}
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": 20
}
```

#### 4.2.6 加入项目

- 方法：`POST`
- 路径：`/projects/{id}/join`
- 权限：`USER`；账号从 JWT 获取，无需在请求体传 `accountId`
- 描述：普通用户携带自己代表的实际参团人数加入项目；后端会锁定项目并校验人数上限

请求示例：

```json
{
  "representedCount": 4
}
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": null
}
```

#### 4.2.7 退出项目

- 方法：`POST`
- 路径：`/projects/{id}/quit`
- 权限：`USER`（`BOTH` 继承该权限）；账号从 JWT 获取
- 描述：普通参与者退出尚未开始的 `OPEN/MATCHING/CONFIRMED` 项目。成员状态更新为 `QUIT`，其代表人数从 `currentMembers` 中释放，并同步退出项目群；项目发布者不能退出，当前领队需先调用放弃带队接口。

请求示例：

```http
POST /projects/1/quit
Authorization: Bearer <token>
```

#### 4.2.8 指定项目领队

- 方法：`POST`
- 路径：`/projects/{id}/leader`
- 权限：`USER`（`BOTH` 继承该权限）；且仅项目 owner 可操作
- 描述：为项目指定领队账号

请求示例：

```json
{
  "leaderAccountId": 3
}
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": null
}
```

#### 4.2.9 领队接单

- 方法：`POST`
- 路径：`/projects/{id}/accept`
- 权限：`LEADER`（`BOTH` 继承该权限）；当前登录用户将作为项目接单领队
- 描述：领队确认接单，接单后项目状态流转

请求示例：

```http
POST /projects/1/accept
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": null
}
```

#### 4.2.10 领队放弃带队并重新开放项目

- 方法：`POST`
- 路径：`/projects/{id}/abandon`
- 权限：`LEADER`（`BOTH` 继承该权限）；仅当前项目领队可操作
- 参数：`reason` 必填，去除首尾空格后 5–200 字；查询串 `?reason=xxx`、JSON 请求体 `{"reason":"xxx"}`、表单参数 `reason=xxx` 三种传法都支持
- 描述：项目处于 `CONFIRMED` 且距出发时间至少 6 小时（恰好 6 小时允许）时，清空 `leaderAccountId`、将状态恢复为 `OPEN`，同步移除群聊领队身份，并写入一条放弃审计记录。时间校验、状态恢复、审计写入与群聊移除在同一事务中。
- 失败：`reason` 缺失 / 空白 / 少于 5 字 / 超过 200 字 → `400`；`departureDate` 或 `departureTime` 缺失 → `400`，文案为“订单时间异常，请联系客服处理”；距出发不足 6 小时、项目非 `CONFIRMED`、非当前接单领队 → `403`
- 依赖：升级已有数据库需先执行 `scripts/create_project_abandon_records.sql` 新建 `project_abandon_records`；表不存在时审计记录写入失败并整笔回滚，接口返回 `500`

请求示例：

```http
POST /projects/1/abandon?reason=临时家里有事，无法带队
Authorization: Bearer <leader-token>
```

#### 4.2.11 查询放弃带队记录

- 方法：`GET`
- 路径：`/projects/{id}/abandon-records`
- 权限：项目发布者、当前接单领队、曾放弃该项目的领队、`ADMIN`；其它账号 `403`
- 描述：按操作时间倒序返回该项目的放弃审计记录，包含项目、领队、原因、放弃前状态、出发时间快照与操作时间。

请求示例：

```http
GET /projects/1/abandon-records
Authorization: Bearer <token>
```

响应结果（`data` 为记录数组）：

```json
{
    "code": 1,
    "msg": "success",
    "data": [
        {
            "id": 1,
            "projectId": 1,
            "leaderAccountId": 3,
            "reason": "临时家里有事，无法带队",
            "previousStatus": "CONFIRMED",
            "departureAt": "2026-11-10T09:00:00",
            "abandonedAt": "2026-10-10T15:30:00",
            "createdAt": "2026-10-10T15:30:00"
        }
    ]
}
```

#### 4.2.12 更新项目状态

- 方法：`POST`
- 路径：`/projects/{id}/status`
- 权限：`USER/LEADER/ADMIN`
- 描述：更新项目状态（状态流转需符合业务规则）

请求示例：

```http
POST /projects/1/status?status=IN_PROGRESS
```

请求参数：

- `status`：目标状态（必填），可选值：`OPEN/MATCHING/CONFIRMED/IN_PROGRESS/DONE/CANCELLED`

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": null
}
```

### 4.3 路线相关

#### 4.3.1 获取路线列表

- 方法：`GET`
- 路径：`/routes`
- 描述：按用户偏好分页获取路线列表

请求示例：

```http
GET /routes?accountId=1&pageNum=1&pageSize=10
```

请求参数：

- `accountId`：用户 ID（必填）
- `pageNum`：页码（可选，默认 `1`）
- `pageSize`：每页数量（可选，默认 `10`）

响应结果：（好吧路线也只有这些）

```json
{
    "code": 1,
    "msg": "success",
    "data": [
        {
            "id": 17,
            "regionAdcode": "320102",
            "tag": "历史人文",
            "containsOutdatedAttractions": true,
            "createdAt": "2026-03-28T19:23:31"
        },
        {
            "id": 4,
            "regionAdcode": null,
            "tag": null,
            "containsOutdatedAttractions": false,
            "createdAt": "2026-03-15T17:34:04"
        },
        {
            "id": 2,
            "regionAdcode": null,
            "tag": null,
            "containsOutdatedAttractions": false,
            "createdAt": "2026-03-12T13:54:50"
        },
        {
            "id": 1,
            "regionAdcode": null,
            "tag": null,
            "containsOutdatedAttractions": false,
            "createdAt": "2026-03-11T20:26:30"
        }
    ]
}
```

#### 4.3.2 获取路线详情

- 方法：`GET`
- 路径：`/routes/{id}`
- 描述：根据路线 ID 获取路线节点

请求示例：

```http
GET /routes/17
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": [
        {
            "routeId": 17,
            "visitOrder": 1,
            "poiId": "B00190BMRC",
            "visitTime": "2026-03-20T09:00:00",
            "recommendedDuration": 120,
            "notes": "建议参观时间：9:00-11:00；可乘坐地铁2号线前往下一个景点",
            "parentPoiId": null,
            "name": "总统府",
            "address": "长江路292号",
            "location": "118.797398,32.044228",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320102",
            "adname": "玄武区",
            "type": "风景名胜;风景名胜;国家级景点",
            "typecode": "110202",
            "distance": null,
            "opentimeToday": null,
            "opentimeWeek": "3月1日-10月14周二至周日:08:30-18:00(17:00停止售票,最入园17:10)；10月15日-次年2月28日)08:30-17:00(16:00停止售票,最入园16:10)；元旦、春节、清明、劳动节、端午、国庆08:30-17:00(16:00停止售票,最入园16:10)；中秋08:30-17:30(16:30停止售票)",
            "tel": "025-84578716;025-84578888",
            "attractionCreatedAt": null,
            "attractionUpdatedAt": null,
            "createdAt": "2026-03-28T19:23:30"
        },
        {
            "routeId": 17,
            "visitOrder": 2,
            "poiId": "B00190AMPT",
            "visitTime": "2026-03-20T11:30:00",
            "recommendedDuration": 90,
            "notes": "建议参观时间：11:30-13:00；步行约1.3公里或乘坐地铁2号线前往下一个景点",
            "parentPoiId": "B00191437W",
            "name": "朝天宫",
            "address": "朝天宫4号(朝天宫地铁站1号口步行210米)",
            "location": "118.775320,32.034344",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320104",
            "adname": "秦淮区",
            "type": "风景名胜;风景名胜;国家级景点",
            "typecode": "110202",
            "distance": null,
            "opentimeToday": null,
            "opentimeWeek": "周一关闭(法定节假日正常开放),周二至周日:09:00-18:00(17:00停止售票),2026年2月1日至2026年3月3日周一正常开放",
            "tel": "025-84200177;025-84466460",
            "attractionCreatedAt": null,
            "attractionUpdatedAt": null,
            "createdAt": "2026-03-28T19:23:30"
        },
        {
            "routeId": 17,
            "visitOrder": 3,
            "poiId": "B001905325",
            "visitTime": "2026-03-20T14:00:00",
            "recommendedDuration": 120,
            "notes": "建议参观时间：14:00-16:00；乘坐地铁5号线前往下一个景点",
            "parentPoiId": null,
            "name": "阅江楼景区",
            "address": "建宁路202号",
            "location": "118.748018,32.094477",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320106",
            "adname": "鼓楼区",
            "type": "风景名胜;风景名胜;国家级景点",
            "typecode": "110202",
            "distance": null,
            "opentimeToday": null,
            "opentimeWeek": "5月 周一至周日 08:00-21:00；1月至4月 周一至周日 08:00-17:30 最晚进入17:00 05-02至12-31 周一至周日 08:00-17:30 最晚进入17:00",
            "tel": "025-58815369",
            "attractionCreatedAt": null,
            "attractionUpdatedAt": null,
            "createdAt": "2026-03-28T19:23:30"
        },
        {
            "routeId": 17,
            "visitOrder": 4,
            "poiId": "B001906CHO",
            "visitTime": "2026-03-21T09:00:00",
            "recommendedDuration": 120,
            "notes": "建议参观时间：9:00-11:00；乘坐550路公交前往上一个景点",
            "parentPoiId": null,
            "name": "宝船厂遗址公园",
            "address": "漓江路57号",
            "location": "118.733421,32.060879",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320106",
            "adname": "鼓楼区",
            "type": "风景名胜;风景名胜;省级景点",
            "typecode": "110203",
            "distance": null,
            "opentimeToday": "06:00-20:30",
            "opentimeWeek": "周一至周日 06:00-20:30",
            "tel": "025-86227011",
            "attractionCreatedAt": null,
            "attractionUpdatedAt": null,
            "createdAt": "2026-03-28T19:23:30"
        }
    ]
}
```

#### 4.3.3 发布路线为项目

- 方法：`POST`
- 路径：`/routes/{routeId}/publish`
- 权限：`USER`（`BOTH` 继承该权限）
- 描述：供路线卡片上的“发布”按钮进入独立详情页后提交；路线 ID、地区和标签由后端确定，发布状态固定为 `OPEN`

请求示例：

```json
{
  "title": "南京历史研学拼单",
  "representedCount": 3,
  "departureDate": "2026-08-01",
  "departureTime": "08:30:00",
  "startPointType": "CURRENT_LOCATION",
  "startPoint": "118.796877,32.060255",
  "leaderRequirements": "熟悉南京历史，可做讲解",
  "participantRequirements": "适合10岁以上学生",
  "maxMembers": null
}
```

字段说明：

- `representedCount`：当前发布账号代表的实际参团人数，必填且大于 `0`
- `departureDate`、`departureTime`：出发日期和时间，必填
- `startPointType`：`CURRENT_LOCATION` 或 `MANUAL`
- `startPoint`：必填；当前位置模式提交前端定位得到的地址或经纬度，手动模式提交输入内容
- `leaderRequirements`、`participantRequirements`：可选
- `maxMembers`：可选；不传或传 `null` 表示不限额

响应中的 `data` 为项目 ID。

#### 4.3.4 手动生成路线

- 方法：`POST`
- 路径：`/routes/manual`
- 描述：手动提交路线节点并生成路线

请求示例：

```json
[
  {
    "poiId": "B00190BMRC",
    "visitOrder": 1,
    "visitTime": "2026-04-01T09:00:00",
    "recommendedDuration": 120,
    "notes": "先参观总统府"
  },
  {
    "poiId": "B00190AMPT",
    "visitOrder": 2,
    "visitTime": "2026-04-01T11:30:00",
    "recommendedDuration": 90,
    "notes": "步行前往朝天宫"
  },
  {
    "poiId": "B001905325",
    "visitOrder": 3,
    "visitTime": "2026-04-01T14:00:00",
    "recommendedDuration": 120,
    "notes": "下午参观阅江楼"
  }
]
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": 18
}
```

#### 4.3.5 AI 生成并优化路线

- 方法：`POST`
- 路径：`/routes/ai/{memoryId}`
- 权限：必须登录，用户身份来自 access token。相同 `memoryId` 在不同账号下分别保存状态和路线映射，不能访问或覆盖其他账号的会话。旧无归属会话缓存不迁移，已保存路线不删除。
- 描述：根据自然语言请求由 AI 生成路线，再自动优化景点顺序、游览时间、建议时长和通勤说明，最后保存优化后的路线。也可提交已有景点数组，直接按该数组优化，保留原 `/routes/optimize` 的输入方式。
- 请求体：可选。提交 1～20 个不重复且有效的景点时，服务直接优化这些景点；不提交请求体时，服务根据 `message` 生成初稿后再优化。

请求示例（自动使用当前登录用户偏好）：

```http
POST /routes/ai/1?message=我要在南京，2026年3月20日开始的两天内进行历史方面的旅游，请给我规划一个路线
```

请求示例（带用户偏好推荐）：

```http
POST /routes/ai/1?message=我想去南京玩两天&accountId=6
```

提交已有景点并优化（原优化路线的请求方式）：

```http
POST /routes/ai/1?message=尽量减少折返，并保留午餐时间
Content-Type: application/json
```

```json
[
  {
    "poiId": "B00190BMRC",
    "visitOrder": 1,
    "visitTime": "2026-04-01T09:00:00",
    "recommendedDuration": 120,
    "notes": "",
    "name": "总统府",
    "location": "118.7969,32.0486",
    "adcode": "320102",
    "citycode": "025"
  },
  {
    "poiId": "B00190AMPT",
    "visitOrder": 2,
    "visitTime": "2026-04-01T14:00:00",
    "recommendedDuration": 90,
    "notes": "",
    "name": "朝天宫",
    "location": "118.7807,32.0314",
    "adcode": "320104",
    "citycode": "025"
  }
]
```

请求参数：

- `message`：未提交请求体时必填；提交景点数组时可选，用于补充优化目标
- `accountId`：兼容参数，可省略；提供时必须等于当前登录用户 ID，否则返回 403。省略时自动使用当前登录用户偏好。
- `memoryId`：非空，最多 128 个字符；由后端结合当前账号隔离，不要在前端拼接账号命名空间。

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": 19
}
```

#### 4.3.6 收藏路线

- 方法：`POST`
- 路径：`/routes/{id}/favorite`
- 描述：当前登录账号收藏指定路线
- 权限：`USER`、`LEADER`（`BOTH` 同样可用）
- 当前账号由 JWT 自动识别，前端不传 `accountId`
- 重复收藏按成功处理

```http
POST /routes/19/favorite
Authorization: Bearer <token>
```

#### 4.3.7 取消收藏路线

- 方法：`DELETE`
- 路径：`/routes/{id}/favorite`
- 描述：当前登录账号取消收藏指定路线
- 权限：`USER`、`LEADER`（`BOTH` 同样可用）
- 当前账号由 JWT 自动识别，前端不传 `accountId`
- 重复取消收藏按成功处理

```http
DELETE /routes/19/favorite
Authorization: Bearer <token>
```

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": null
}
```

#### 4.3.8 路线规划 Agent 接口（异步任务）

按前端 `docs/API.md`、`docs/openapi.json` 实现的异步规划接口。完整字段与取值范围以 `docs/openapi.json`
为准，本节只说明后端接入方式与实现边界。

- `POST /routes/agent/runs`：创建一轮异步规划，立即返回 HTTP 202 和任务视图；模型与地图查询在后台执行
- `GET /routes/agent/runs/{runId}`：查询阶段、事件与结果，建议每 1.8～2 秒轮询，终态后停止
- `DELETE /routes/agent/runs/{runId}`：取消规划，返回与查询一致的 Run 对象
- `GET /health`：本服务健康检查，无需登录，返回 `{"status":"ok","version":"1.0"}`

权限与身份：

- 前三个接口必须携带 `Authorization: Bearer <access token>`；未登录或 token 无效返回 401
- 请求体中的 `accountId` 必须等于登录账号，否则返回 403；账号身份一律以 token 为准
- 查询、取消其他账号的任务与“任务不存在”同样返回 404
- 响应是直接 JSON 对象，不包裹 `Result`；错误体为 `{"error": "..."}`；
  参数或未知字段校验失败返回 HTTP 422 和 FastAPI 风格 `{"detail": [{"type","loc","msg","input","ctx"}]}`

创建请求示例：

```http
POST /routes/agent/runs
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "requestId": "7e547d16-f693-4c21-b143-0123456789ab",
  "memoryId": "user-1-route-conversation-01",
  "accountId": 1,
  "message": "南京亲子历史研学，控制在4小时，预算每人200元，尽量少走路，必须去南京博物院",
  "history": [],
  "currentRoute": [],
  "constraints": {
    "city": "南京",
    "days": 1,
    "dailyMinutes": 240,
    "budget": 200,
    "maxWalkingMeters": 2000,
    "modes": ["walking", "transit"],
    "mustVisit": ["南京博物院"]
  },
  "origin": null
}
```

创建响应（HTTP 202）：

```json
{
  "runId": "a67f99cfbb9f4532a1ee009988776655",
  "status": "queued",
  "stage": "等待规划",
  "events": [],
  "result": null,
  "error": null
}
```

查询响应的 `status` 为 `queued`、`running`、`completed`、`failed`、`cancelled`。
HTTP 200 不等于任务成功：`completed` 时再看 `result.kind`，`plan` 是方案、`clarification` 是追问；
任务失败时为 HTTP 200 + `status=failed` + `error`。

关键行为：

- 幂等：同账号、同 `requestId`、同请求体重复提交返回已有任务（HTTP 202，不重复规划）；
  同 `requestId` 不同内容返回 409
- 会话互斥：同账号同 `memoryId` 同时只能有一个活跃任务，否则返回 409
- 限流：单账号 60 秒内最多新建 6 次，全局活跃任务最多 8 个，保存任务上限 500 条，
  超出返回 429；幂等重复不占用新建额度
- 任务保留：从创建时刻算 30 分钟，运行中不清理，终态记录在后续请求时清理
- 取消：`DELETE` 只结束后台任务并释放会话锁，不删除路线与账号数据；已完成/失败/取消的任务返回现有终态；
  取消在检查点生效，已经在执行中的模型或地图调用可能仍会完成
- 结果不落库：方案通过 `requiresAcceptance=true` 提示客户端确认后再应用；
  发布沿用原 `/routes/manual` 和发布接口

运行限制（`application.yaml` 中的 `app.agent.*`，均为可配置项）：

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `app.agent.execution-timeout` | 240s | 单轮整体执行时间上限，超时任务置为 failed |
| `app.agent.run-ttl` | 30m | 终态任务保留时间 |
| `app.agent.max-stored-runs` | 500 | 保存任务上限 |
| `app.agent.max-active-runs` | 8 | 全局活跃任务上限 |
| `app.agent.create-window` / `max-creates-per-window` | 60s / 6 | 单账号新建频率 |
| `app.agent.max-events` | 110 | 每次查询返回的事件条数上限 |
| `app.agent.worker-threads` | 4 | 后台执行线程数 |
| `app.agent.max-direction-requests` | 90 | 单轮最多发起的高德路径规划请求数 |

交通段的高德路径规划由 `AmapDirectionClient` 完成，相关配置同样可覆盖：
`app.amap.walking-endpoint`、`app.amap.driving-endpoint`、`app.amap.transit-endpoint`
（默认分别为高德 v5 的 walking / driving / transit/integrated 地址）、
`app.amap.direction-timeout-seconds`（10）、`app.amap.direction-qps`（8）、
`app.amap.direction-cache-ttl-minutes`（10，同一路径短时间内重复读直接复用，不再发起请求）。
未配置 `app.amap.key`、端点不可用或高德返回错误时，交通段退化为直线估算并写入 `warnings`。

与 `docs/API.md` 的已知差异（联调前请确认）：

- 任务存储为进程内存，单 worker 部署；多 worker 随机分发时不能依赖内存任务与限流，与文档第 8 节说明一致
- 规划主体复用 `POST /routes/ai/{memoryId}` 的既有链路（向量/图谱召回 + 模型），
  事件是“理解需求 / 检索候选地点 / 核实地点详情 / 计算交通段 / 校验行程与约束 / 方案已完成”这几个真实步骤，
  不是文档示例里的模型内部工具调用流水；当前没有接入天气工具，因此不会出现 `get_weather` 事件
- 结构化 `constraints` 会拼成一段约束摘要随 `message` 传给既有规划链路，属于适配而非形式化约束求解
- 景点库没有票价字段，因此 `estimatedCost` 目前只统计高德返回的已知交通费用（通常为 0），
  `unknownCosts` 会把每个未核价的地点和交通段都算进去，并写入 `warnings`
- 高德路径规划不可用（未配置 key、鉴权失败、限流）时，交通段退化为直线距离 + 经验速度估算，
  `source=直线估算`，`walkingMeters=null`，并写入 `warnings`，不会当成已知值
- 硬约束（必去地点、每天时长、最晚结束时间、步行上限）冲突时返回 `result.kind=clarification` 追问，
  不会返回 `feasible=false` 的方案（契约中 `feasible` 恒为 `true`、`issues` 恒为空数组）
- `memoryId` 沿用后端既有会话键上限 128 个字符（文档放宽到 160），超过 128 直接返回 422，
  不会等到后台执行时才失败

外部身份校验接口：`GET /login/me` 供需要单独部署 Agent 服务的场景核验 token
（本后端内联实现 Agent 时用不到，JWT 过滤器已直接校验同一批 token），
返回 `{"code":1,"msg":"success","data":{"accountId":1}}`，未登录返回 401；账号来自 token，不接受请求方指定。

### 4.4 评论相关

#### 4.4.1 创建评论

- 方法：`POST`
- 路径：`/reviews`
- 描述：创建评论并可提交标签分数 （5分制）（reviewType暂时只有USER_TO_LEADER）
- 作者由当前登录账号确定；请求中的 `fromAccountId` 被忽略，可省略。

请求示例：

```json
{
  "projectId": 1,
  "routeId": 2,
  "fromAccountId": 1,
  "toAccountId": 3,
  "reviewType": "USER_TO_LEADER",
  "overallScore": 5,
  "content": "整体体验很好"
}
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": 1
}
```

#### 4.4.2 删除评论

- 方法：`DELETE`
- 路径：`/reviews/{id}`
- 描述：按评论 ID 删除评论
- 权限：仅评论作者或 `ADMIN`；其他用户返回 403，记录不存在返回 404。

请求示例：

```http
DELETE /reviews/10
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": null
}
```

#### 4.4.3 修改评论

- 方法：`PUT`
- 路径：`/reviews/{id}`
- 描述：更新评论主体与标签分数
- 权限：仅评论作者或 `ADMIN`；仅评分、内容可修改。作者、项目、路线、被评价账号和评价类型以数据库原记录为准，不接受通过更新迁移归属。

请求示例：

```json
{
  "projectId": 1,
  "routeId": 2,
  "fromAccountId": 1,
  "toAccountId": 3,
  "reviewType": "USER_TO_LEADER",
  "overallScore": 4,
  "content": "更新后的评论内容"
}
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": null
}
```

#### 4.4.4 获取评论详情

- 方法：`GET`
- 路径：`/reviews/{id}`
- 描述：获取单条评论及其标签分数

请求示例：

```http
GET /reviews/1
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": {
        "id": 1,
        "projectId": 1,
        "routeId": 2,
        "fromAccountId": 1,
        "toAccountId": 3,
        "reviewType": "USER_TO_LEADER",
        "overallScore": 4,
        "content": "更新后的评论内容",
        "createdAt": "2026-03-30T19:16:06"
    }
}
```

#### 4.4.5 获取全部评论

- 方法：`GET`
- 路径：`/reviews`
- 描述：获取全部评论列表

请求示例：

```http
GET /reviews
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": [
        {
            "id": 1,
            "projectId": 1,
            "routeId": 2,
            "fromAccountId": 1,
            "toAccountId": 3,
            "reviewType": "USER_TO_LEADER",
            "overallScore": 4,
            "content": "更新后的评论内容",
            "createdAt": "2026-03-30T19:16:06"
        },
        {
            "id": 2,
            "projectId": 1,
            "routeId": 2,
            "fromAccountId": 1,
            "toAccountId": 3,
            "reviewType": "USER_TO_LEADER",
            "overallScore": 5,
            "content": "整体体验很好",
            "createdAt": "2026-03-30T19:21:49"
        },
        {
            "id": 3,
            "projectId": 1,
            "routeId": 2,
            "fromAccountId": 1,
            "toAccountId": 3,
            "reviewType": "USER_TO_LEADER",
            "overallScore": 5,
            "content": "整体体验很好",
            "createdAt": "2026-03-30T19:22:26"
        }
    ]
}
```

#### 4.4.6 按项目查询评论

- 方法：`GET`
- 路径：`/reviews/project/{projectId}`
- 描述：查询指定项目的评论

请求示例：

```http
GET /reviews/project/1
```

响应结果同上

#### 4.4.7 按路线查询评论

- 方法：`GET`
- 路径：`/reviews/route/{routeId}`
- 描述：查询指定路线的评论

请求示例：

```http
GET /reviews/route/2
```

响应结果同上

#### 4.4.8 查询领队收到的评论

- 方法：`GET`
- 路径：`/reviews/leader/{accountId}`
- 描述：查询目标用户收到的评论列表

请求示例：

```http
GET /reviews/leader/3
```

响应结果同上

#### 4.4.9 查询某用户发出的评论

- 方法：`GET`
- 路径：`/reviews/user/{accountId}`
- 描述：查询指定用户发出的评论列表

请求示例：

```http
GET /reviews/user/1
```

响应结果同上

#### 4.4.10 获取用户平均评分

- 方法：`GET`
- 路径：`/reviews/average-score/{accountId}`
- 描述：获取目标用户收到评论的平均分

请求示例：

```http
GET /reviews/average-score/3
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": 4.6667
}
```

### 4.5 聊天相关

聊天采用“一项目一群”：

- 项目发布时自动创建唯一群聊，发布者身份为 `PUBLISHER`
- 项目成员加入后自动加入群聊，身份为 `PARTICIPANT`
- 领队接单后自动加入群聊，身份为 `LEADER`
- 同时提供显式创建、加入接口，均为幂等操作
- 项目进入 `DONE/CANCELLED` 或发布者删除群组后，群组软删除并停止发消息，历史消息保留
- 升级已有数据库依次执行
  `scripts/alter_projects_add_participation_details.sql`、
  `scripts/alter_chat_sessions_to_project_groups.sql`、
  `scripts/alter_chat_group_lifecycle.sql`

#### 4.5.0 群组生命周期

创建或恢复项目群，仅项目发布者可操作：

```http
POST /chat/groups
Authorization: Bearer <token>
Content-Type: application/json

{
  "projectId": 39
}
```

加入群组，仅项目发布者、领队或已参团成员可操作：

```http
POST /chat/groups/{sessionId}/join
Authorization: Bearer <token>
```

退出群聊，仅当前处于群内的普通成员（`PARTICIPANT`）可操作；退出只影响群聊成员关系，
不会退出项目，项目发布者和领队不能调用此接口：

```http
DELETE /chat/groups/{sessionId}/members/me
Authorization: Bearer <token>
```

退出成功后成员状态更新为 `LEFT`，该群不再出现在群聊列表中，且无法继续收发群消息；
重复退出按成功处理。如需重新加入群聊，可调用上述加入群组接口。

删除群组，仅项目发布者或管理员可操作：

```http
DELETE /chat/groups/{sessionId}
Authorization: Bearer <token>
```

`GET /chat/groups` 是 `GET /chat/sessions` 的群组语义别名。

#### 4.5.1 查询群聊列表

- 方法：`GET`
- 路径：`/chat/sessions`
- 描述：查询当前登录用户已加入的活动项目群聊（账号从 JWT 自动识别）

请求示例：

```http
GET /chat/sessions
```

说明：

- `accountId` 与 `role` 由 JWT 自动识别，无需传参

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": [
        {
            "id": 1,
            "projectId": 1,
            "userAccountId": 2,
            "leaderAccountId": 3,
            "status": "ACTIVE",
            "disabledAt": null,
            "createdAt": "2026-03-30T19:45:34",
            "currentUserRole": "PUBLISHER",
            "memberCount": 3
        }
    ]
}
```

#### 4.5.2 查询群成员及代表人数

- 方法：`GET`
- 路径：`/chat/sessions/{sessionId}/members`
- 描述：仅群成员可查询；参团账号返回实际代表人数，领队若不参团则返回 `0`

响应示例：

```json
{
  "code": 1,
  "msg": "success",
  "data": [
    {
      "accountId": 2,
      "username": "张三",
      "avatarUrl": null,
      "memberRole": "PUBLISHER",
      "representedCount": 3,
      "representationText": "该用户代表3人"
    }
  ]
}
```

#### 4.5.3 发送群聊消息

- 方法：`POST`
- 路径：`/chat/messages`
- 说明：`senderAccountId` 由 JWT 自动识别，前端无需传
- 描述：项目群成员发送消息（目前只支持文本）；群组停用后不能继续发送

请求示例：

```json
{
  "sessionId": 1,
  "content": "你好，行程细节可以再确认一下吗？",
  "msgType": "TEXT"
}
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": 1
}
```

#### 4.5.4 拉取群聊消息

- 方法：`GET`
- 路径：`/chat/sessions/{sessionId}/messages`
- 描述：项目群成员分页拉取指定群聊的历史消息；群组停用后历史消息仍保留

请求参数：

- `pageNum`：历史页码，默认 `1`；第 `1` 页是最近一页，数字越大消息越早
- `pageSize`：每页消息数，默认 `30`，最大 `100`
- 每页数据内部仍按发送时间正序返回，方便前端直接按时间展示

请求示例：

```http
GET /chat/sessions/1/messages?pageNum=1&pageSize=30
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": [
        {
            "id": 1,
            "sessionId": 1,
            "senderAccountId": 2,
            "content": "你好，行程细节可以再确认一下吗？",
            "msgType": "TEXT",
            "sentAt": "2026-03-30T19:52:36"
        }
    ]
}
```

### 4.6 数据相关（地区与景点）

#### 4.6.1 获取省级地区

- 方法：`GET`
- 路径：`/regions/provinces`
- 描述：获取省级行政区列表（注册时用，无港澳台）

请求示例：

```http
GET /regions/provinces
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": [
        {
            "adcode": "110000",
            "name": "北京市",
            "level": 1,
            "parentAdcode": null,
            "citycode": "010",
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "120000",
            "name": "天津市",
            "level": 1,
            "parentAdcode": null,
            "citycode": "022",
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "130000",
            "name": "河北省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "140000",
            "name": "山西省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "150000",
            "name": "内蒙古自治区",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "210000",
            "name": "辽宁省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "220000",
            "name": "吉林省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "230000",
            "name": "黑龙江省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "310000",
            "name": "上海市",
            "level": 1,
            "parentAdcode": null,
            "citycode": "021",
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "320000",
            "name": "江苏省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "330000",
            "name": "浙江省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "340000",
            "name": "安徽省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "350000",
            "name": "福建省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "360000",
            "name": "江西省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "370000",
            "name": "山东省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "410000",
            "name": "河南省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "420000",
            "name": "湖北省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "430000",
            "name": "湖南省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "440000",
            "name": "广东省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "450000",
            "name": "广西壮族自治区",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "460000",
            "name": "海南省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "500000",
            "name": "重庆市",
            "level": 1,
            "parentAdcode": null,
            "citycode": "023",
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "510000",
            "name": "四川省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "520000",
            "name": "贵州省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "530000",
            "name": "云南省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "540000",
            "name": "西藏自治区",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "610000",
            "name": "陕西省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "620000",
            "name": "甘肃省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "630000",
            "name": "青海省",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "640000",
            "name": "宁夏回族自治区",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        },
        {
            "adcode": "650000",
            "name": "新疆维吾尔自治区",
            "level": 1,
            "parentAdcode": null,
            "citycode": null,
            "isVirtual": 0,
            "hasChildren": 1
        }
    ]
}
```

#### 4.6.2 获取下级地区

- 方法：`GET`
- 路径：`/regions/children`
- 描述：按父级行政区编码查询下级地区（注册时用，一共三级地区）

请求示例：（第二级地区）

```http
GET /regions/children?parentAdcode=110000
```

请求参数：

- `parentAdcode`：父级行政区编码（必填）

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": [
        {
            "adcode": "110100",
            "name": "北京市",
            "level": 2,
            "parentAdcode": "110000",
            "citycode": "010",
            "isVirtual": 1,
            "hasChildren": 1
        }
    ]
}
```

请求示例：（第三级地区）

```http
GET /regions/children?parentAdcode=110100
```

请求参数：

- `parentAdcode`：父级行政区编码（必填）

响应结果：

```json
{
  "code": 1,
  "msg": "success",
  "data": [
    {
      "adcode": "110101",
      "name": "东城区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110102",
      "name": "西城区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110105",
      "name": "朝阳区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110106",
      "name": "丰台区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110107",
      "name": "石景山区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110108",
      "name": "海淀区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110109",
      "name": "门头沟区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110111",
      "name": "房山区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110112",
      "name": "通州区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110113",
      "name": "顺义区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110114",
      "name": "昌平区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110115",
      "name": "大兴区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110116",
      "name": "怀柔区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110117",
      "name": "平谷区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110118",
      "name": "密云区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    },
    {
      "adcode": "110119",
      "name": "延庆区",
      "level": 3,
      "parentAdcode": "110100",
      "citycode": "010",
      "isVirtual": 0,
      "hasChildren": 0
    }
  ]
}
```

#### 4.6.3 获取景点列表

- 方法：`GET`
- 路径：`/attractions`
- 描述：按地区分页查询景点，`regionCode` 为空时查全部

请求示例：

```http
GET /attractions?regionCode=320100&pageNum=1&pageSize=10
```

请求参数：

- `regionCode`：地区编码（可选）
- `pageNum`：页码（可选，默认 `1`）
- `pageSize`：每页数量（可选，默认 `10`）

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": [
        {
            "poiId": "B00190513A",
            "parentPoiId": null,
            "name": "南京国防园",
            "address": "虎踞路87号",
            "location": "118.754817,32.051368",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320106",
            "adname": "鼓楼区",
            "type": "风景名胜;风景名胜;省级景点",
            "typecode": "110203",
            "distance": null,
            "createdAt": "2026-03-20T18:51:16",
            "updatedAt": "2026-03-20T18:51:16"
        },
        {
            "poiId": "B001905179",
            "parentPoiId": null,
            "name": "乌龙潭公园",
            "address": "广州路215号(省人民医院对面)",
            "location": "118.766475,32.046112",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320106",
            "adname": "鼓楼区",
            "type": "风景名胜;风景名胜;省级景点",
            "typecode": "110203",
            "distance": null,
            "createdAt": "2026-03-20T18:51:16",
            "updatedAt": "2026-03-20T18:51:16"
        },
        {
            "poiId": "B001905325",
            "parentPoiId": null,
            "name": "阅江楼景区",
            "address": "建宁路202号",
            "location": "118.748018,32.094477",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320106",
            "adname": "鼓楼区",
            "type": "风景名胜;风景名胜;国家级景点",
            "typecode": "110202",
            "distance": null,
            "createdAt": "2026-03-20T18:51:16",
            "updatedAt": "2026-03-20T18:51:16"
        },
        {
            "poiId": "B001905N9D",
            "parentPoiId": null,
            "name": "侵华日军南京大屠杀遇难同胞纪念馆",
            "address": "水西门大街418号",
            "location": "118.742372,32.035217",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320105",
            "adname": "建邺区",
            "type": "风景名胜;风景名胜;纪念馆",
            "typecode": "110204",
            "distance": null,
            "createdAt": "2026-03-20T18:51:16",
            "updatedAt": "2026-03-20T18:51:16"
        },
        {
            "poiId": "B00190682O",
            "parentPoiId": null,
            "name": "中国南京云锦博物馆",
            "address": "茶亭东街240号",
            "location": "118.744814,32.036390",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320105",
            "adname": "建邺区",
            "type": "科教文化服务;博物馆;博物馆",
            "typecode": "140100",
            "distance": null,
            "createdAt": "2026-03-20T18:51:16",
            "updatedAt": "2026-03-20T18:51:16"
        },
        {
            "poiId": "B001906CHO",
            "parentPoiId": null,
            "name": "宝船厂遗址公园",
            "address": "漓江路57号",
            "location": "118.733421,32.060879",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320106",
            "adname": "鼓楼区",
            "type": "风景名胜;风景名胜;省级景点",
            "typecode": "110203",
            "distance": null,
            "createdAt": "2026-03-20T18:51:16",
            "updatedAt": "2026-03-20T18:51:16"
        },
        {
            "poiId": "B001906U94",
            "parentPoiId": "B001905325",
            "name": "南京静海寺纪念馆",
            "address": "建宁路288号",
            "location": "118.744723,32.092359",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320106",
            "adname": "鼓楼区",
            "type": "风景名胜;风景名胜;纪念馆",
            "typecode": "110204",
            "distance": null,
            "createdAt": "2026-03-20T18:51:16",
            "updatedAt": "2026-03-20T18:51:16"
        },
        {
            "poiId": "B001907IVB",
            "parentPoiId": null,
            "name": "中共代表团梅园新村纪念馆",
            "address": "汉府街18-1",
            "location": "118.801602,32.042379",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320102",
            "adname": "玄武区",
            "type": "风景名胜;风景名胜;纪念馆",
            "typecode": "110204",
            "distance": null,
            "createdAt": "2026-03-20T18:51:16",
            "updatedAt": "2026-03-20T18:51:16"
        },
        {
            "poiId": "B001908DLT",
            "parentPoiId": null,
            "name": "南京美术馆",
            "address": "四条巷12号(近常府街)",
            "location": "118.798451,32.034392",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320104",
            "adname": "秦淮区",
            "type": "科教文化服务;美术馆;美术馆",
            "typecode": "140400",
            "distance": null,
            "createdAt": "2026-03-20T18:51:16",
            "updatedAt": "2026-03-20T18:51:16"
        },
        {
            "poiId": "B001909T86",
            "parentPoiId": null,
            "name": "江苏科技馆",
            "address": "石头城路118号",
            "location": "118.749855,32.066072",
            "pcode": "320000",
            "pname": "江苏省",
            "citycode": "025",
            "cityname": "南京市",
            "adcode": "320106",
            "adname": "鼓楼区",
            "type": "科教文化服务;科技馆;科技馆",
            "typecode": "140600",
            "distance": null,
            "createdAt": "2026-03-20T18:51:16",
            "updatedAt": "2026-03-20T18:51:16"
        }
    ]
}
```
