# Study Tour API 文档

## 1. 基础信息

- 项目名称：`study_tour`
- 技术栈：`Spring Boot 3 + MyBatis + MySQL`
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
  "passwordHash": "123456",
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
- 描述：仅当前接单领队可操作。项目必须处于 `CONFIRMED` 且尚未到出发时间；成功后清空领队并将项目恢复为 `OPEN`，其他领队可重新接单。

```http
POST /projects/20/abandon
Authorization: Bearer <leader-token>
```

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
- 描述：注册普通用户或领队用户

请求示例：

```json
{
  "username": "B",
  "phone": "10010001003",
  "password": "123456",
  "confirmPassword": "123456",
  "role": "Both",
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
            "passwordHash": "$2a$10$TyGCL9uuMIS7p4u7VT4u/eP0jnRCM0lpELK9wLbej2v.IHffa9AoC",
            "regionCode": "320102",
            "avatarUrl": null,
            "intro": "喜欢把城市历史、博物馆展览和实地观察结合起来，偏好节奏清晰的研学路线。",
            "status": 1,
            "createdAt": "2026-03-30T16:37:02",
            "updatedAt": "2026-03-30T16:37:02"
        },
        "token": "eyJhbGciOiJIUzI1NiJ9.eyJhY2NvdW50SWQiOjYsInJvbGUiOiJCT1RIIiwic3ViIjoiNiIsImlhdCI6MTc3NDg2MDk3NCwiZXhwIjoxNzc0OTQ3Mzc0fQ.QJpzeDOqgYuyyfe1WC1UuNydQy1ip16lt5Z-lXig2_E",
        "refreshToken": "eyJhbGciOiJIUzI1NiJ9.eyJhY2NvdW50SWQiOjYsInN1YiI6IjYiLCJpYXQiOjE3NzQ4NjA5NzQsImV4cCI6MTc3NTQ2NTc3NH0.vCx7buXk5EackWc-_pd5kzQnpYH5SqPJZPsn4doFcUE"
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
        "passwordHash": "$2a$10$TyGCL9uuMIS7p4u7VT4u/eP0jnRCM0lpELK9wLbej2v.IHffa9AoC",
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
  "avatarUrl": "https://study-tour-image.oss-cn-beijing.aliyuncs.com/avatar.png",
  "status": 1
}
```

请求字段：

- `username`：用户名（可选）
- `regionCode`：所在地区编码（可选）
- `avatarUrl`：头像 URL（可选，通常先调用 `/upload` 获取）
- `status`：账号状态（可选）

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
        "passwordHash": "$2a$10$TyGCL9uuMIS7p4u7VT4u/eP0jnRCM0lpELK9wLbej2v.IHffa9AoC",
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
- 权限：仅本人可改；`ADMIN` 可改任意用户。普通用户只能设置 `USER/LEADER/BOTH`，`ADMIN` 角色仅允许管理员设置。
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
            "passwordHash": "$2a$10$TyGCL9uuMIS7p4u7VT4u/eP0jnRCM0lpELK9wLbej2v.IHffa9AoC",
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
        "passwordHash": "$2a$10$TyGCL9uuMIS7p4u7VT4u/eP0jnRCM0lpELK9wLbej2v.IHffa9AoC",
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
- 描述：项目处于 `CONFIRMED` 且未到出发时间时，清空 `leaderAccountId`、将状态恢复为 `OPEN`，并同步移除群聊领队身份。

请求示例：

```http
POST /projects/1/abandon
Authorization: Bearer <leader-token>
```

#### 4.2.11 更新项目状态

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
- 描述：根据自然语言请求由 AI 生成路线，再自动优化景点顺序、游览时间、建议时长和通勤说明，最后保存优化后的路线。优化过程中可按需求增删候选景点。

请求示例（不带用户偏好）：

```http
POST /routes/ai/1?message=我要在南京，2026年3月20日开始的两天内进行历史方面的旅游，请给我规划一个路线
```

请求示例（带用户偏好推荐）：

```http
POST /routes/ai/1?message=我想去南京玩两天&accountId=6
```

请求参数：

- `message`：用户的自然语言需求（必填）
- `accountId`：用户 ID（可选，传入后会融合用户长期偏好标签，使 AI 生成的路线更贴合个人兴趣）

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": 19
}
```

### 4.4 评论相关

#### 4.4.1 创建评论

- 方法：`POST`
- 路径：`/reviews`
- 描述：创建评论并可提交标签分数 （5分制）（reviewType暂时只有USER_TO_LEADER）

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
- 描述：项目群成员拉取指定群聊的历史消息；群组停用后历史消息仍保留

请求示例：

```http
GET /chat/sessions/1/messages
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
