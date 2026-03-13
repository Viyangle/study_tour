# Study Tour API 文档

## 1. 基础信息

- 项目名称：`study_tour`
- 技术栈：`Spring Boot 3 + MyBatis + MySQL`
- 默认服务地址：`http://10.6.86.86`
- 连通性检查：`GET /login/ping`
- 数据格式：`application/json`

## 2. 统一返回格式

所有接口统一返回 `Result`：

```json
{
  "code": 1,
  "msg": "success",
  "data": {}
}
```

字段说明：

- `code`：`1` 成功，`0` 失败
- `msg`：响应消息
- `data`：响应数据，新增/修改接口通常为 `null`

## 3. 数据模型

### 3.1 Account

```json
{
  "id": 1,
  "role": "USER",
  "username": "V",
  "phone": "15151091129",
  "passwordHash": "123456",
  "regionCode": "210000",
  "avatarUrl": "https://study-tour-image.oss-cn-beijing.aliyuncs.com/xxxx.png",
  "status": 1,
  "createdAt": "2026-03-11T13:42:05",
  "updatedAt": "2026-03-11T13:42:05"
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

标签参考：

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

## 4. 接口列表

### 4.1 用户注册

- 方法：`POST`
- 路径：`/register`
- 描述：注册用户；`role=LEADER` 时会初始化领队档案

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

### 4.2 用户登录

- 方法：`POST`
- 路径：`/login`

请求体：

```json
{
  "phone": "15151091129",
  "passwordHash": "123456"
}
```

补充：

- 方法：`GET`
- 路径：`/login/ping`
- 返回：纯文本 `ok`

### 4.3 用户信息

- `GET /accounts/{id}`：获取用户详情
- `GET /accounts/{id}/tagPrefs`：获取用户标签偏好
- `POST /accounts/{id}/tagPrefs`：覆盖更新用户标签偏好
- `GET /accounts/{id}/leaderProfile`：获取领队资料
- `POST /accounts/{id}/intro`：更新领队简介（仅 LEADER 生效）

### 4.4 项目与路线

- `GET /attractions`：获取全部景点
- `POST /routes/manual`：手动生成路线，返回 `routeId`
- `GET /routes/{id}`：按路线 ID 获取路线节点
- `POST /routes/ai`：未实现

- `GET /projects`：获取全部项目
- `GET /projects/{id}`：获取项目详情
- `GET /projects/{id}/members`：获取项目成员
- `POST /projects`：创建项目
- `POST /projects/{id}/join`：加入项目
- `POST /projects/{id}/leader`：指定项目领队

### 4.5 文件上传（OSS）

- 方法：`POST`
- 路径：`/upload`
- 描述：上传图片到阿里云 OSS，返回可访问 URL
- `Content-Type`：`multipart/form-data`
- 表单字段：`image`（file）

请求示例（curl）：

```bash
curl -X POST "http://10.6.86.86/upload" \
  -F "image=@D:/tmp/avatar.png"
```

成功响应：

```json
{
  "code": 1,
  "msg": "success",
  "data": "https://study-tour-image.oss-cn-beijing.aliyuncs.com/xxxx-xxxx.png"
}
```

失败场景：

- 文件为空，或表单字段名不是 `image`
- OSS 凭证未配置（环境变量 `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET`）
- 文件超限（单文件 `10MB`，总请求 `100MB`）

### 4.6 修改用户头像（avatarUrl）

- 方法：`POST`
- 路径：`/accounts/{id}/avatar`
- 描述：写入 `accounts.avatar_url`

请求体：

```json
{
  "avatarUrl": "https://study-tour-image.oss-cn-beijing.aliyuncs.com/xxxx-xxxx.png"
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

推荐调用顺序：

1. 先调用 `POST /upload` 拿到图片 URL
2. 再调用 `POST /accounts/{id}/avatar` 写回头像 URL

注意：

- 当前 `accountMapper.updateById` 为全字段更新，只传 `avatarUrl` 可能覆盖其他字段为 `null`
- 建议前端传完整 `Account` 字段，或后端改为动态更新 SQL

