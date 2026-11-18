# 景点研学讲解接口 · 前端联调通知

更新日期：2026-11-18

适用范围：`GET /attractions/{poiId}/guide`

完整字段和错误说明见 [README.md 的 4.6.6 节](../README.md#466-获取景点研学讲解)，本文件只列前端接入需要知道的行为和展示建议。

> 一句话：新增一个只读讲解接口；原有景点接口和路线接口不变。前端在景点详情页按 `poiId` 拉取即可，不需要调用 AI，也不需要自己抓百科。

## 一、接口

```http
GET /attractions/{poiId}/guide
Authorization: Bearer <access-token>
```

- 需要登录，未认证是 HTTP 401。
- 路径参数是景点 `poiId`，通常来自路线节点或景点列表，不需要 URL 编码处理特殊字符。
- 没有请求体、没有分页参数。
- 没有批量接口，不要在路线列表页为每个景点并发拉取；进入某个景点详情时再请求。

## 二、成功响应

```json
{
  "code": 1,
  "msg": "success",
  "data": {
    "poiId": "B00190BBCZ",
    "name": "玄武湖景区",
    "address": "玄武门路1号钟山风景名胜区西侧",
    "summary": "玄武湖位于江苏南京，六朝前称桑泊……",
    "content": "玄武湖，又名桑泊、秣陵湖……",
    "studyPoints": ["梳理玄武湖景区的基本概况与主要特色"],
    "questions": ["玄武湖景区最值得关注的特色是什么？"],
    "visitTips": "请以景点官方开放信息和现场指引为准；遵守参观秩序，不触碰展品、文物或遗址。",
    "coverUrl": null,
    "audioUrl": null,
    "sourceName": "求闻百科",
    "sourceUrl": "https://www.qiuwenbaike.cn/wiki/玄武湖",
    "sourceType": "QIUWEN",
    "sourceRevision": "6616009",
    "sourceLicense": "CC BY-SA 4.0",
    "sourceAttribution": "求闻百科编者；内容经整理或 AI 改写，依 CC BY-SA 4.0 提供",
    "syncStatus": "SOURCED",
    "syncedAt": "2026-11-18T13:54:00",
    "updatedAt": "2026-11-18T13:54:00"
  }
}
```

### 字段可空性

| 字段 | 说明 |
|---|---|
| `summary` | 正常都有值，可直接作为详情页摘要 |
| `content` | **可能为 `null`**，尤其是 `syncStatus=FALLBACK` 的景点 |
| `studyPoints` / `questions` | 前端直接按数组使用；没有内容时是 `[]`，不要自己解析 JSON 字符串 |
| `coverUrl` / `audioUrl` | 可能为 `null`；当前不要假设一定有图或语音 |
| `sourceName` / `sourceUrl` | 用于展示“资料来自哪里”；不能保证所有来源都有 URL |
| `sourceType` | 来源类型：`QIUWEN` / `BAIDU_BAIKE_MOBILE` / `WIKIPEDIA` / `BAIDU_BAIKE` / `PARENT` / `AMAP` / `MANUAL` |
| `sourceRevision` / `sourceLicense` / `sourceAttribution` | 可选，可能为 `null`；有值时建议展示 |
| `syncStatus` | 见下方展示建议 |
| `syncedAt` / `updatedAt` | ISO 本地时间，无时区偏移，按服务端 `Asia/Shanghai` 时间展示 |

以下内部字段不会返回：`source_text`、`source_hash`、`sync_error`、`auto_update`、`status`。

## 三、`syncStatus` 展示建议

| 值 | 含义 | 前端建议 |
|---|---|---|
| `SOURCED` | 有通过校验的百科正文 | 正常展示讲解、知识点、思考题和来源 |
| `PARENT` | 当前点位没有独立词条，只拿到所属景点背景 | 加“所属景点背景”标识，不要显示成当前点位的独立史实介绍 |
| `FALLBACK` | 只有高德基础资料 | 显示“资料待补充”，可展示摘要和现场观察任务，不要暗示有完整历史讲解 |

当前部署库 63 个景点均有讲解：

- `SOURCED` 43 条
- `PARENT` 4 条
- `FALLBACK` 16 条

因此正常不会因为“没有讲解”而空白；新同步的景点在后台回填完成前可能短暂 404。

## 四、404 与错误处理

景点没有讲解、讲解未发布或景点不存在时：

```http
HTTP/1.1 404 Not Found
Content-Type: application/json
```

```json
{
  "code": 0,
  "msg": "景点研学讲解不存在或未发布, poiId=B00190513A",
  "data": null
}
```

建议：

- 404 时隐藏讲解模块或显示“暂无讲解”，**不要弹全局错误**，更不要自动重试；
- 401 按现有登录失效流程处理；
- 500 显示通用错误，不要把它当成景点不存在。

## 五、前端接入位置

1. 景点详情页：进入时请求 `/attractions/{poiId}/guide`，把 `summary` 作为摘要，`content` 作为正文。
2. 路线节点：用户点击某个站点后再请求，不要在路线列表页批量请求。
3. 研学任务区：`studyPoints` 和 `questions` 是字符串数组，直接按列表渲染。
4. 来源区：展示 `sourceName`，有 `sourceUrl` 时做成可点击来源链接；有 `sourceLicense` 时一并展示。
5. `FALLBACK` 状态：可弱化正文区，突出“资料待补充”和现场观察任务。

## 六、兼容性与上线状态

- 这是**新增接口**，不修改 `GET /attractions/{poiId}` 的字段，也不修改路线接口；未接入的前端不受影响。
- 服务端已部署，`GET /attractions/{poiId}/guide` 从本次版本开始可用。
- 不使用 AI 问答，也没有讲解对话接口；当前页面只展示已入库内容。
- 讲解内容会由后台自动刷新，前端不需要在用户侧触发同步。
- AI 知识点/思考题依赖服务端 `CHAT_MODEL_KEY`；当前远端 Key 返回 401 时仍是保守模板，但接口结构不变，修复 Key 后再次 `resync` 即可更新内容，前端无需改代码。

## 七、联调复测清单

1. 带有效 token 请求一个已知景点：HTTP 200，`code=1`，`studyPoints/questions` 是数组。
2. 请求不存在的 `poiId`：HTTP 404，`code=0`，页面不崩溃。
3. 不带 token：HTTP 401。
4. 分别找一个 `SOURCED`、`PARENT`、`FALLBACK` 景点，确认三种标识和正文展示符合预期。
5. 找一个 `coverUrl=null` 的景点，确认图片占位正常。
6. 点击来源链接，确认 `sourceUrl` 能正常打开。
