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

- `role`：用户角色，为`USER`或`LEADER`
- `username`：用户名
- `phone`：手机号，需唯一
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


### 4.11 获取全部项目

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

### 4.12 获取项目详情

- 方法：`GET`
- 路径：`/projects/{id}`
- 描述：根据项目 ID 获取项目详情

示例：

`GET /projects/1`

成功响应：
```json
{
    "code": 1,
    "msg": "success",
    "data": {
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
}
```

### 4.13 获取项目成员

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

### 4.14 创建项目

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

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": null
}
```

### 4.15 加入项目

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

### 4.16 指定项目领队

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

### 4.17 文件上传（OSS）

- 方法：`POST`
- 路径：`/upload`
- `Content-Type`：`multipart/form-data`
- 表单字段：`image`（file）

请求示例（curl）：

```bash
curl -X POST "http://10.6.86.86/upload" \
  -F "image=@D:/tmp/avatar.png"
```

成功响应（`data` 为图片 URL）：

```json
{
  "code": 1,
  "msg": "success",
  "data": "https://study-tour-image.oss-cn-beijing.aliyuncs.com/28afeeb9-afa8-4e16-99e2-4f8aac53a5c3.jpg"
}
```

注意事项：

- 单文件限制：`1MB`
- 请求总大小限制：`10MB`

### 4.18 AI 规划路线

- 方法：`POST`
- 路径：`/routes/ai/{memoryId}`
- 接口说明：根据 `message` 调用 AI 生成路线，并返回新建 `routeId`

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| memoryId | string | 是 | AI 对话记忆 ID |

请求参数（query/form）：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| message | string | 是 | 用户的路线规划请求 |

请求示例：

```http
POST /routes/ai/1?message=帮我规划一条北京两天研学路线
```

成功响应示例（`data` 为 `routeId`）：

```json
{
  "code": 1,
  "msg": "success",
  "data": 4
}
```

