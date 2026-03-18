# Study Tour API 文档

## 1. 基础信息

- 项目名称：`study_tour`
- 技术栈：`Spring Boot 3 + MyBatis + MySQL`
- 默认服务地址：`http://10.6.86.86`
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
| tagId | tagName |
|------|---------|
| 1 | 历史人文 |
| 2 | 博物馆研学 |
| 3 | 非遗体验 |
| 4 | 科技探索 |
| 5 | 自然生态 |
| 6 | 地理地质 |
| 7 | 航天航空 |
| 8 | 农耕劳动 |
| 9 | 艺术美育 |
| 10 | 红色教育 |
| 11 | 高校参访 |
| 12 | 职业启蒙 |
| 13 | 英语实践 |
| 14 | 摄影记录 |
| 15 | 亲子互动 |

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
  "maxMembers": 30,
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
  "joinedAt": "2026-03-12T21:00:00"
}
```

## 4. 接口列表

### 4.1 用户注册

- 方法：`POST`
- 路径：`/register`
- 描述：注册普通用户或领队用户；当 `role=LEADER` 时会同时初始化领队档案

请求体：

```json
{
  "role": "LEADER",
  "username": "Leader",
  "phone": "10010001001",
  "passwordHash": "123456",
  "regionCode": "210000"
}
```

请求字段：

- `role`：用户角色，当前代码中常见值为 `LEADER`
- `username`：用户名
- `phone`：手机号
- `passwordHash`：密码字段，当前实际仍是明文比对，尚未做 BCrypt/JWT
- `regionCode`：地区编码

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": null
}
```

失败响应：

```json
{
  "code": 0,
  "msg": "用户名已存在",
  "data": null
}
```

```json
{
  "code": 0,
  "msg": "手机号已存在",
  "data": null
}
```

### 4.2 用户登录

- 方法：`POST`
- 路径：`/login`
- 描述：按手机号和密码登录

请求体：

```json
{
  "phone": "15151091129",
  "passwordHash": "123456"
}
```

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": {
    "id": 1,
    "role": "USER",
    "username": "V",
    "phone": "15151091129",
    "passwordHash": "123456",
    "regionCode": "210000",
    "avatarUrl": null,
    "status": 1,
    "createdAt": "2026-03-11T13:42:05",
    "updatedAt": "2026-03-11T13:42:05"
  }
}
```

失败响应：

```json
{
  "code": 0,
  "msg": "用户名或密码错误",
  "data": null
}
```

补充接口：

- 方法：`GET`
- 路径：`/login/ping`
- 描述：网络检查
- 成功响应：纯文本 `ok`

### 4.3 获取用户详情

- 方法：`GET`
- 路径：`/accounts/{id}`
- 描述：根据用户 ID 获取用户信息

示例：

`GET /accounts/1`

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": {
    "id": 1,
    "role": "USER",
    "username": "V",
    "phone": "15151091129",
    "passwordHash": "123456",
    "regionCode": "210000",
    "avatarUrl": null,
    "status": 1,
    "createdAt": "2026-03-11T13:42:05",
    "updatedAt": "2026-03-11T13:42:05"
  }
}
```

### 4.4 获取用户标签偏好

- 方法：`GET`
- 路径：`/accounts/{id}/tagPrefs`
- 描述：获取指定用户的标签偏好列表

示例：

`GET /accounts/1/tagPrefs`

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": [
    {
      "accountId": 1,
      "tagId": 1
    },
    {
      "accountId": 1,
      "tagId": 2
    }
  ]
}
```

### 4.5 修改用户标签偏好

- 方法：`POST`
- 路径：`/accounts/{id}/tagPrefs`
- 描述：覆盖式更新用户标签偏好；服务端会先删除该用户原有偏好，再批量插入新偏好

请求体：

```json
[
  {
    "accountId": 1,
    "tagId": 1
  },
  {
    "accountId": 1,
    "tagId": 2
  }
]
```

注意：

- 路径参数 `id` 在当前实现中只用于路由匹配，实际写入以请求体里的 `accountId` 为准
- 请求体不能为空，否则服务层会因读取首元素报错

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": null
}
```

### 4.6 获取领队资料

- 方法：`GET`
- 路径：`/accounts/{id}/leaderProfile`
- 描述：获取指定账号的领队资料

示例：

`GET /accounts/3/leaderProfile`

成功响应：

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

### 4.7 修改领队简介

- 方法：`POST`
- 路径：`/accounts/{id}/intro`
- 描述：仅当账号角色为 `LEADER` 时会实际更新

请求体：

```json
{
  "intro": "test"
}
```

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": null
}
```

### 4.8 获取全部景点

- 方法：`GET`
- 路径：`/attractions`
- 描述：获取所有景点列表，当前未分页

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": [
    {
      "id": 1,
      "name": "故宫博物院",
      "type": "MUSEUM",
      "location": "北京市东城区景山前街4号",
      "regionCode": "100000",
      "description": "中国明清两代皇家宫殿",
      "recommendedDuration": 180,
      "createdAt": "2026-03-11T20:23:50",
      "updatedAt": "2026-03-11T20:23:50"
    },
    {
      "id": 2,
      "name": "长城",
      "type": "HISTORIC_SITE",
      "location": "北京市延庆区八达岭镇",
      "regionCode": "100000",
      "description": "世界文化遗产",
      "recommendedDuration": 240,
      "createdAt": "2026-03-11T20:23:50",
      "updatedAt": "2026-03-11T20:23:50"
    }
  ]
}
```

### 4.9 手动生成路线

- 方法：`POST`
- 路径：`/routes/manual`
- 描述：创建一条新路线，并保存多个路线景点节点

请求体：

```json
[
  {
    "attractionId": 2,
    "visitOrder": 1,
    "visitTime": "08:00:00",
    "recommendedDuration": 240,
    "notes": "需提前到场"
  },
  {
    "attractionId": 1,
    "visitOrder": 2,
    "visitTime": "16:00:00",
    "recommendedDuration": 180,
    "notes": "通勤时间长"
  }
]
```

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": 2
}
```

说明：

- `data` 返回新生成的 `routeId`

### 4.10 获取路线详情

- 方法：`GET`
- 路径：`/routes/{id}`
- 描述：根据路线 ID 获取路线中的景点节点列表

示例：

`GET /routes/2`

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": [
    {
      "id": 6,
      "routeId": 2,
      "attractionId": 1,
      "visitOrder": 2,
      "visitTime": "16:00:00",
      "recommendedDuration": 180,
      "notes": "通勤时间长",
      "createdAt": "2026-03-12T13:54:50"
    },
    {
      "id": 5,
      "routeId": 2,
      "attractionId": 2,
      "visitOrder": 1,
      "visitTime": "08:00:00",
      "recommendedDuration": 240,
      "notes": "需提前到场",
      "createdAt": "2026-03-12T13:54:50"
    }
  ]
}
```

### 4.11 AI 生成路线

- 方法：`POST`
- 路径：`/routes/ai`
- 状态：未实现
- 说明：当前控制器方法直接返回 `null`，不建议前端接入

### 4.12 获取全部项目

- 方法：`GET`
- 路径：`/projects`
- 描述：获取所有项目列表，当前未分页

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": [
    {
      "id": 1,
      "routeId": 2,
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

### 4.13 获取项目详情

- 方法：`GET`
- 路径：`/projects/{id}`
- 描述：根据项目 ID 获取项目详情

示例：

`GET /projects/1`

### 4.14 获取项目成员

- 方法：`GET`
- 路径：`/projects/{id}/members`
- 描述：获取项目成员列表

示例：

`GET /projects/1/members`

成功响应：

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
      "joinedAt": "2026-03-12T16:38:12"
    },
    {
      "id": 2,
      "projectId": 1,
      "accountId": 2,
      "joinStatus": "JOINED",
      "joinedAt": "2026-03-12T19:18:53"
    }
  ]
}
```

### 4.15 创建项目

- 方法：`POST`
- 路径：`/projects`
- 描述：创建项目，同时自动把项目发起人写入项目成员表

请求体：

```json
{
  "routeId": 2,
  "ownerAccountId": 1,
  "title": "test",
  "departureDate": "2026-03-12",
  "maxMembers": 3,
  "currentMembers": 1,
  "status": "OPEN"
}
```

说明：

- `leaderAccountId` 可选
- 当前代码未在 service 中手动设置 `createdAt/updatedAt`，依赖数据库默认值更稳妥

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": null
}
```

### 4.16 加入项目

- 方法：`POST`
- 路径：`/projects/{id}/join`
- 描述：普通用户加入项目，路径参数 `id` 为项目 ID

请求体：

```json
{
  "accountId": 2
}
```

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": null
}
```

### 4.17 指定项目领队

- 方法：`POST`
- 路径：`/projects/{id}/leader`
- 描述：更新项目信息中的领队账号，实际调用为按 ID 局部更新项目

请求体示例：

```json
{
  "leaderAccountId": 3
}
```

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": null
}
```

## Postman 测试指南

### 环境准备
1. **数据库**：确保MySQL数据库已启动，数据库名为`study_tour`，用户名为`love`，密码为`lovepoems`。
2. **Java**：确保安装了JDK 17或更高版本。
3. **Maven**：确保安装了Maven。
4. **Postman**：下载并安装Postman。

### 启动应用
1. 进入项目根目录：`cd D:\dachuang\study_tour`
2. 运行Maven命令：`mvn spring-boot:run`
3. 应用将在`http://localhost:8080`启动。

### Postman配置
- Base URL: `http://localhost:8080`
- 设置环境变量（如需要）。

### API测试步骤

#### 1. 健康检查
- **方法**: GET
- **URL**: `/login/ping`
- **描述**: 检查服务是否正常运行。
- **预期响应**: `"ok"`

#### 2. 用户注册
- **方法**: POST
- **URL**: `/register`
- **Headers**: `Content-Type: application/json`
- **Body** (JSON):
  ```json
  {
    "username": "testuser",
    "phone": "13800138000",
    "password": "password123",
    "confirmPassword": "password123",
    "role": "USER"
  }
  ```
- **描述**: 注册新用户。
- **预期响应**: 成功时返回`{"code":1,"msg":"success"}`，失败时返回错误信息。

#### 3. 用户登录
- **方法**: POST
- **URL**: `/login`
- **Headers**: `Content-Type: application/json`
- **Body** (JSON):
  ```json
  {
    "phone": "13800138000",
    "password": "password123"
  }
  ```
- **描述**: 用户登录，获取JWT token。
- **预期响应**: 成功时返回`{"code":1,"msg":"success","data":{"account":{...},"token":"jwt_token"}}`。
- **注意**: 保存token用于后续需要认证的请求。

#### 4. 获取用户信息
- **方法**: GET
- **URL**: `/accounts/{id}` (替换{id}为用户ID，如1)
- **Headers**: `Authorization: Bearer {token}` (如果需要认证)
- **描述**: 获取指定用户的信息。
- **预期响应**: 返回用户信息。

#### 5. 获取用户标签偏好
- **方法**: GET
- **URL**: `/accounts/{id}/tagPrefs`
- **描述**: 获取用户的标签偏好。
- **预期响应**: 返回标签偏好列表。

#### 6. 修改用户标签偏好
- **方法**: POST
- **URL**: `/accounts/{id}/tagPrefs`
- **Headers**: `Content-Type: application/json`
- **Body** (JSON): 标签偏好列表
- **描述**: 修改用户的标签偏好。

#### 7. 获取领队简介
- **方法**: GET
- **URL**: `/accounts/{id}/leaderProfile`
- **描述**: 获取领队的简介。

#### 8. 修改领队简介
- **方法**: POST
- **URL**: `/accounts/{id}/intro`
- **Headers**: `Content-Type: application/json`
- **Body** (JSON):
  ```json
  {
    "intro": "新的简介内容"
  }
  ```

#### 9. 获取所有景点
- **方法**: GET
- **URL**: `/attractions`
- **描述**: 获取所有景点信息。
- **预期响应**: 返回景点列表。

#### 10. 获取所有项目
- **方法**: GET
- **URL**: `/projects`
- **描述**: 获取所有项目。
- **预期响应**: 返回项目列表。

#### 11. 获取项目详情
- **方法**: GET
- **URL**: `/projects/{id}`
- **描述**: 获取指定项目的详情。

#### 12. 获取项目成员
- **方法**: GET
- **URL**: `/projects/{id}/members`
- **描述**: 获取项目的成员列表。

#### 13. 创建项目
- **方法**: POST
- **URL**: `/projects`
- **Headers**: `Content-Type: application/json`
- **Body** (JSON): 项目信息
- **描述**: 创建新项目。

#### 14. 加入项目
- **方法**: POST
- **URL**: `/projects/{id}/join`
- **Headers**: `Content-Type: application/json`
- **Body** (JSON):
  ```json
  {
    "accountId": 1
  }
  ```

#### 15. 创建聊天会话
- **方法**: POST
- **URL**: `/chat/sessions`
- **Headers**: `Content-Type: application/json`
- **Body** (JSON):
  ```json
  {
    "projectId": 1,
    "userAccountId": 1,
    "leaderAccountId": 2
  }
  ```
- **描述**: 创建或获取聊天会话。

#### 16. 查询会话列表
- **方法**: GET
- **URL**: `/chat/sessions?accountId=1&role=USER`
- **描述**: 查询用户的会话列表。

#### 17. 发送聊天消息
- **方法**: POST
- **URL**: `/chat/messages`
- **Headers**: `Content-Type: application/json`
- **Body** (JSON): 消息内容
- **描述**: 发送聊天消息。

#### 18. 获取路线
- **方法**: GET
- **URL**: `/routes/{id}`
- **描述**: 获取指定路线。

#### 19. 手动生成路线
- **方法**: POST
- **URL**: `/routes/manual`
- **Headers**: `Content-Type: application/json`
- **Body** (JSON): 路线景点列表
- **描述**: 手动生成路线。

#### 20. AI生成路线
- **方法**: POST
- **URL**: `/routes/ai`
- **描述**: AI生成路线（当前未实现，返回null）。

### 注意事项
- 某些接口可能需要JWT token认证，请在Headers中添加`Authorization: Bearer {token}`。
- 评价功能（ReviewController）尚未实现。
- 确保数据库中有相应的数据，否则某些查询可能返回空结果。
- 如果遇到错误，检查控制台日志或数据库连接。

### 故障排除
- 如果端口冲突，修改`application.yaml`中的server.port。
- 确保MySQL服务运行，并数据库已创建。
- 使用`mvn clean install`重新构建项目。
