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
  "phone": "10010001003",
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

- 鉴权：4.1.5 ~ 4.1.10 接口均需请求头携带 `Authorization: Bearer <token>`

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
        "status": 1,
        "createdAt": "2026-03-30T16:37:02",
        "updatedAt": "2026-03-30T16:37:02"
    }
}
```

#### 4.1.6 获取用户标签偏好

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

#### 4.1.7 修改用户标签偏好

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

#### 4.1.8 获取领队资料

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

#### 4.1.9 修改领队简介

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

#### 4.1.10 文件上传（OSS）

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
- 描述：按用户偏好分页获取项目列表

请求示例：

```http
GET /projects?accountId=6&pageNum=1&pageSize=10
```

请求参数：

- `accountId`：用户 ID（必填）
- `pageNum`：页码（可选，默认 `1`， 起始为`1`）
- `pageSize`：每页数量（可选，默认 `10`）

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

#### 4.2.2 获取项目详情

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

#### 4.2.3 获取项目成员

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

#### 4.2.4 创建项目

- 方法：`POST`
- 路径：`/projects`
- 权限：`USER/LEADER`；`ownerAccountId` 由当前登录用户决定（前端传入会被覆盖）
- 描述：创建项目

请求示例：

```json
{
  "routeId": 2,
  "leaderAccountId": 3,
  "title": "北京历史文化研学",
  "departureDate": "2026-03-12",
  "maxMembers": 30,
  "currentMembers": 1,
  "status": "OPEN"
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

#### 4.2.5 加入项目

- 方法：`POST`
- 路径：`/projects/{id}/join`
- 权限：`USER`；账号从 JWT 获取，无需在请求体传 `accountId`
- 描述：普通用户加入项目

请求示例：

```http
POST /projects/1/join
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": null
}
```

#### 4.2.6 指定项目领队

- 方法：`POST`
- 路径：`/projects/{id}/leader`
- 权限：`USER/LEADER`；且仅项目 owner 可操作
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
            "createdAt": "2026-03-28T19:23:31"
        },
        {
            "id": 4,
            "regionAdcode": null,
            "tag": null,
            "createdAt": "2026-03-15T17:34:04"
        },
        {
            "id": 2,
            "regionAdcode": null,
            "tag": null,
            "createdAt": "2026-03-12T13:54:50"
        },
        {
            "id": 1,
            "regionAdcode": null,
            "tag": null,
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
            "attractionCreatedAt": null,
            "attractionUpdatedAt": null,
            "createdAt": "2026-03-28T19:23:30"
        }
    ]
}
```

#### 4.3.3 手动生成路线

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

#### 4.3.4 AI 规划路线

- 方法：`POST`
- 路径：`/routes/ai/{memoryId}`
- 描述：根据自然语言请求由 AI 生成并保存路线

请求示例：

```http
POST /routes/ai/1?message=我要在南京，2026年3月20日开始的两天内进行历史方面的旅游，请给我规划一个路线
```

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

#### 4.5.1 创建或获取会话

- 方法：`POST`
- 路径：`/chat/sessions`
- 描述：按项目和双方账号创建或复用会话

请求示例：

```json
{
  "projectId": 1,
  "userAccountId": 2,
  "leaderAccountId": 3
}
```

响应结果：

```json
{
    "code": 1,
    "msg": "success",
    "data": {
        "id": 1,
        "projectId": 1,
        "userAccountId": 2,
        "leaderAccountId": 3,
        "createdAt": "2026-03-30T19:45:34"
    }
}
```

#### 4.5.2 查询会话列表

- 方法：`GET`
- 路径：`/chat/sessions`
- 描述：查询当前登录用户的会话列表（账号与角色从 JWT 自动识别）

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
            "createdAt": "2026-03-30T19:45:34"
        }
    ]
}
```

#### 4.5.3 发送消息

- 方法：`POST`
- 路径：`/chat/messages`
- 说明：`senderAccountId` 由 JWT 自动识别，前端无需传
- 描述：向会话发送消息（目前先只用文本消息）

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

#### 4.5.4 拉取会话消息

- 方法：`GET`
- 路径：`/chat/sessions/{sessionId}/messages`
- 描述：拉取指定会话的消息列表

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
