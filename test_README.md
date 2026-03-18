# Study Tour Postman 测试指南

## 概述
本指南提供详细的Postman测试步骤，用于测试Study Tour项目的各种API接口。包括用户注册、登录、创建项目、创建聊天会话、获取景点、生成路线等功能。每个步骤都包含完整的请求配置和预期响应。

## 环境准备
1. **数据库**：确保MySQL数据库已启动，数据库名为`study_tour`，用户名为`love`，密码为`lovepoems`。
2. **Java**：确保安装了JDK 17或更高版本。
3. **Maven**：确保安装了Maven。
4. **Postman**：下载并安装Postman。

## 启动应用
1. 进入项目根目录：`cd D:\dachuang\study_tour`
2. 运行Maven命令：`mvn spring-boot:run`
3. 应用将在`http://localhost:8080`启动。

## Postman配置
- **Base URL**: `http://localhost:8080`
- **Headers**: 对于需要认证的接口，在Headers中添加 `Authorization: Bearer {token}`，其中{token}是从登录接口获取的JWT token。
- **Content-Type**: 对于POST请求，添加Header `Content-Type: application/json`

## 测试步骤

### 步骤1: 健康检查
- **目的**: 验证服务是否正常运行。
- **方法**: GET
- **URL**: `{{base_url}}/login/ping`
- **Headers**: 无
- **Body**: 无
- **预期响应**:
  ```
  ok
  ```

### 步骤2: 用户注册（注册普通用户）
- **目的**: 创建一个普通用户账户。
- **方法**: POST
- **URL**: `{{base_url}}/register`
- **Headers**:
  - `Content-Type: application/json`
- **Body** (raw JSON):
  ```json
  {
    "role": "USER",
    "username": "testuser",
    "phone": "13800138000",
    "password": "password123",
    "confirmPassword": "password123",
    "regionCode": "110000"
  }
  ```
  - `role`: 用户角色，"USER"表示普通用户，"LEADER"表示领队。
  - `username`: 用户名，字符串。
  - `phone`: 手机号，字符串。
  - `password`: 密码，字符串（当前实现中仍为明文，后续会改为BCrypt加密）。
  - `confirmPassword`: 确认密码，必须与password相同。
  - `regionCode`: 地区编码，字符串，如"110000"表示北京市。
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": null
  }
  ```
- **注意**: 如果用户名或手机号已存在，会返回错误。

### 步骤3: 用户注册（注册领队用户）
- **目的**: 创建一个领队用户账户。
- **方法**: POST
- **URL**: `{{base_url}}/register`
- **Headers**:
  - `Content-Type: application/json`
- **Body** (raw JSON):
  ```json
  {
    "role": "LEADER",
    "username": "testleader",
    "phone": "13800138001",
    "password": "password123",
    "confirmPassword": "password123",
    "regionCode": "110000"
  }
  ```
- **预期响应**: 同上。
- **注意**: 注册领队时，会同时初始化领队档案。

### 步骤4: 用户登录
- **目的**: 用户登录并获取JWT token。
- **方法**: POST
- **URL**: `{{base_url}}/login`
- **Headers**:
  - `Content-Type: application/json`
- **Body** (raw JSON):
  ```json
  {
    "phone": "13800138000",
    "password": "password123"
  }
  ```
  - `phone`: 注册时使用的手机号。
  - `password`: 注册时使用的密码。
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": {
      "account": {
        "id": 1,
        "role": "USER",
        "username": "testuser",
        "phone": "13800138000",
        "passwordHash": "password123",
        "regionCode": "110000",
        "avatarUrl": null,
        "status": 1,
        "createdAt": "2026-03-17T10:00:00",
        "updatedAt": "2026-03-17T10:00:00"
      },
      "token": "eyJhbGciOiJIUzI1NiJ9..."
    }
  }
  ```
- **注意**: 保存`data.token`的值，用于后续需要认证的请求。在Postman中，可以设置为环境变量，如`{{token}}`。

### 步骤5: 获取用户信息
- **目的**: 根据用户ID获取用户信息。
- **方法**: GET
- **URL**: `{{base_url}}/accounts/{id}` (替换{id}为登录后获取的用户ID，如1)
- **Headers**:
  - `Authorization: Bearer {{token}}`
- **Body**: 无
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": {
      "id": 1,
      "role": "USER",
      "username": "testuser",
      "phone": "13800138000",
      "passwordHash": "password123",
      "regionCode": "110000",
      "avatarUrl": null,
      "status": 1,
      "createdAt": "2026-03-17T10:00:00",
      "updatedAt": "2026-03-17T10:00:00"
    }
  }
  ```

### 步骤6: 修改用户标签偏好
- **目的**: 设置用户的标签偏好。
- **方法**: POST
- **URL**: `{{base_url}}/accounts/{id}/tagPrefs` (替换{id}为用户ID)
- **Headers**:
  - `Authorization: Bearer {{token}}`
  - `Content-Type: application/json`
- **Body** (raw JSON):
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
  - `accountId`: 用户ID，必须与URL中的{id}一致。
  - `tagId`: 标签ID，参考标签表（1:历史人文, 2:博物馆研学, 等）。
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": null
  }
  ```

### 步骤7: 获取所有景点
- **目的**: 获取系统中所有景点的列表。
- **方法**: GET
- **URL**: `{{base_url}}/attractions`
- **Headers**: 无（或可选添加Authorization）
- **Body**: 无
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": [
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
    ]
  }
  ```

### 步骤8: 手动生成路线
- **目的**: 创建一条新路线，包含多个景点节点。
- **方法**: POST
- **URL**: `{{base_url}}/routes/manual`
- **Headers**:
  - `Authorization: Bearer {{token}}`
  - `Content-Type: application/json`
- **Body** (raw JSON):
  ```json
  [
    {
      "attractionId": 1,
      "visitOrder": 1,
      "visitTime": "08:00:00",
      "recommendedDuration": 180,
      "notes": "上午优先参观故宫"
    },
    {
      "attractionId": 2,
      "visitOrder": 2,
      "visitTime": "14:00:00",
      "recommendedDuration": 240,
      "notes": "下午参观长城"
    }
  ]
  ```
  - `attractionId`: 景点ID，从获取景点接口获取。
  - `visitOrder`: 访问顺序，整数。
  - `visitTime`: 访问时间，格式"HH:mm:ss"。
  - `recommendedDuration`: 推荐停留时间，分钟。
  - `notes`: 备注，字符串。
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": 1
  }
  ```
  - `data`: 新生成的路线ID。

### 步骤9: 创建项目
- **目的**: 创建一个新的研学项目。
- **方法**: POST
- **URL**: `{{base_url}}/projects`
- **Headers**:
  - `Authorization: Bearer {{token}}`
  - `Content-Type: application/json`
- **Body** (raw JSON):
  ```json
  {
    "routeId": 1,
    "ownerAccountId": 1,
    "leaderAccountId": 2,
    "title": "北京历史文化研学之旅",
    "departureDate": "2026-04-01",
    "maxMembers": 30,
    "currentMembers": 1,
    "status": "OPEN"
  }
  ```
  - `routeId`: 路线ID，从生成路线接口获取。
  - `ownerAccountId`: 项目发起人ID，通常为当前登录用户ID。
  - `leaderAccountId`: 领队ID，可选。
  - `title`: 项目标题，字符串。
  - `departureDate`: 出发日期，格式"YYYY-MM-DD"。
  - `maxMembers`: 最大成员数，整数。
  - `currentMembers`: 当前成员数，整数（通常为1，包括发起人）。
  - `status`: 项目状态，"OPEN"表示开放。
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": null
  }
  ```
- **注意**: 创建项目时，会自动将发起人加入项目成员。

### 步骤10: 获取所有项目
- **目的**: 获取系统中所有项目的列表。
- **方法**: GET
- **URL**: `{{base_url}}/projects`
- **Headers**:
  - `Authorization: Bearer {{token}}`
- **Body**: 无
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": [
      {
        "id": 1,
        "routeId": 1,
        "ownerAccountId": 1,
        "leaderAccountId": 2,
        "title": "北京历史文化研学之旅",
        "departureDate": "2026-04-01",
        "maxMembers": 30,
        "currentMembers": 1,
        "status": "OPEN",
        "createdAt": "2026-03-17T10:00:00",
        "updatedAt": "2026-03-17T10:00:00"
      }
    ]
  }
  ```

### 步骤11: 加入项目
- **目的**: 用户加入指定的项目。
- **方法**: POST
- **URL**: `{{base_url}}/projects/{id}/join` (替换{id}为项目ID，如1)
- **Headers**:
  - `Authorization: Bearer {{token}}`
  - `Content-Type: application/json`
- **Body** (raw JSON):
  ```json
  {
    "accountId": 1
  }
  ```
  - `accountId`: 要加入项目的用户ID，通常为当前登录用户ID。
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": null
  }
  ```

### 步骤12: 创建聊天会话
- **目的**: 创建或获取项目相关的聊天会话。
- **方法**: POST
- **URL**: `{{base_url}}/chat/sessions`
- **Headers**:
  - `Authorization: Bearer {{token}}`
  - `Content-Type: application/json`
- **Body** (raw JSON):
  ```json
  {
    "projectId": 1,
    "userAccountId": 1,
    "leaderAccountId": 2
  }
  ```
  - `projectId`: 项目ID。
  - `userAccountId`: 用户ID。
  - `leaderAccountId`: 领队ID。
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": {
      "id": 1,
      "projectId": 1,
      "userAccountId": 1,
      "leaderAccountId": 2,
      "createdAt": "2026-03-17T10:00:00",
      "updatedAt": "2026-03-17T10:00:00"
    }
  }
  ```

### 步骤13: 查询会话列表
- **目的**: 获取用户的聊天会话列表。
- **方法**: GET
- **URL**: `{{base_url}}/chat/sessions?accountId=1&role=USER`
- **Headers**:
  - `Authorization: Bearer {{token}}`
- **Body**: 无
- **Query Parameters**:
  - `accountId`: 用户ID。
  - `role`: 用户角色，"USER"或"LEADER"。
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": [
      {
        "id": 1,
        "projectId": 1,
        "userAccountId": 1,
        "leaderAccountId": 2,
        "createdAt": "2026-03-17T10:00:00",
        "updatedAt": "2026-03-17T10:00:00"
      }
    ]
  }
  ```

### 步骤14: 发送聊天消息
- **目的**: 在会话中发送消息。
- **方法**: POST
- **URL**: `{{base_url}}/chat/messages`
- **Headers**:
  - `Authorization: Bearer {{token}}`
  - `Content-Type: application/json`
- **Body** (raw JSON):
  ```json
  {
    "sessionId": 1,
    "senderId": 1,
    "content": "你好，领队！",
    "messageType": "TEXT"
  }
  ```
  - `sessionId`: 会话ID。
  - `senderId`: 发送者ID。
  - `content`: 消息内容。
  - `messageType`: 消息类型，"TEXT"。
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": null
  }
  ```

### 步骤15: 获取项目成员
- **目的**: 获取指定项目的成员列表。
- **方法**: GET
- **URL**: `{{base_url}}/projects/{id}/members` (替换{id}为项目ID)
- **Headers**:
  - `Authorization: Bearer {{token}}`
- **Body**: 无
- **预期响应**:
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
        "joinedAt": "2026-03-17T10:00:00"
      }
    ]
  }
  ```

### 步骤16: 指定项目领队
- **目的**: 为项目指定领队。
- **方法**: POST
- **URL**: `{{base_url}}/projects/{id}/leader` (替换{id}为项目ID)
- **Headers**:
  - `Authorization: Bearer {{token}}`
  - `Content-Type: application/json`
- **Body** (raw JSON):
  ```json
  {
    "leaderAccountId": 2
  }
  ```
  - `leaderAccountId`: 领队用户ID。
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": null
  }
  ```

### 步骤17: 修改领队简介
- **目的**: 修改领队的简介（仅限领队角色）。
- **方法**: POST
- **URL**: `{{base_url}}/accounts/{id}/intro` (替换{id}为领队用户ID)
- **Headers**:
  - `Authorization: Bearer {{token}}`
  - `Content-Type: application/json`
- **Body** (raw JSON):
  ```json
  {
    "intro": "我是一位经验丰富的研学领队，擅长历史文化讲解。"
  }
  ```
  - `intro`: 简介内容，字符串。
- **预期响应**:
  ```json
  {
    "code": 1,
    "msg": "success",
    "data": null
  }
  ```

## 注意事项
- 所有需要认证的接口必须在Headers中包含`Authorization: Bearer {token}`。
- 日期格式统一为"YYYY-MM-DD"，时间为"HH:mm:ss"。
- 如果接口返回错误，检查Body格式、参数值和数据库数据。
- 当前项目中，密码仍为明文存储，后续会实现BCrypt加密。
- 某些接口如AI生成路线尚未实现，会返回null。
- 确保数据库中有相应的景点和路线数据，否则创建项目时会失败。

## 故障排除
- **端口冲突**: 修改`application.yaml`中的`server.port`。
- **数据库连接失败**: 检查MySQL服务和配置。
- **认证失败**: 确保token有效且未过期。
- **数据不存在**: 先创建依赖数据，如景点、路线。
- **重新构建**: 使用`mvn clean install`重新构建项目。</content>
<parameter name="filePath">D:\dachuang\study_tour\test_README.md
