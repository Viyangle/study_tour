# 前端联调通知 · 放弃带队与「我的项目」阶段筛选（P0 清单）

更新日期：2026-10-10
对应后端清单：`docs/backend-change-checklist.md`（6 项 P0 已全部实现）
影响范围：`POST /projects/{id}/abandon`、`GET /projects/mine`、新增 `GET /projects/{id}/abandon-records`

> 一句话：放弃带队现在**必须带放弃原因**，并且**距出发不足 6 小时不能放弃**；「我的项目」新增
> `stage` 阶段筛选，**非法值从“静默忽略”改成 400**。所有接口的响应格式不变，仍是 `{code,msg,data}`（与
> `/routes/agent/**` 的裸 JSON 不同）。

## 一、`POST /projects/{id}/abandon`（有破坏性变化）

TIP：正常返回仍是 `{"code":1,"msg":"success","data":null}`。

### 1. `reason` 现在是必填

- 去除首尾空格后 **5–200 字**；
- 三种传法**都支持**，任选其一即可：

```http
POST /projects/20/abandon?reason=临时家里有事,无法带队
Authorization: Bearer <leader-token>
```

```http
POST /projects/20/abandon
Authorization: Bearer <leader-token>
Content-Type: application/json

{"reason": "临时家里有事,无法带队"}
```

```http
POST /projects/20/abandon
Authorization: Bearer <leader-token>
Content-Type: application/x-www-form-urlencoded

reason=临时家里有事,无法带队
```

- 缺失、空白、少于 5 字、超过 200 字 → **HTTP 400**，`{"code":0,"msg":"放弃原因不能为空 / 放弃原因不能少于 5 个字 / 放弃原因不能超过 200 个字","data":null}`
- 旧客户端如果还是裸 POST（不带任何 reason），会直接 400。

### 2. 新增时间规则（服务器时间判断）

| 情况 | 结果 |
| --- | --- |
| 距出发时间 **≥ 6 小时**（恰好 6 小时允许） | 允许放弃 |
| 距出发时间不足 6 小时（例如 5 小时 59 分） | **403**，`msg` 含剩余分钟数 |
| 项目 `departureDate` 或 `departureTime` 缺失 | **400**，`msg` 为「订单时间异常，请联系客服处理」，不会用默认时间继续 |
| 项目状态不是 `CONFIRMED` | **403** |
| 当前登录账号不是该项目接单领队 | **403** |

成功后的效果与以前一致：清空 `leaderAccountId`、项目恢复 `OPEN`、移除群聊领队身份；此外会写入一条放弃审计记录。
时间校验、状态恢复、审计写入、群聊变更现在在同一事务中，任一步失败会整体回滚（不会出现半完成状态）。

## 二、`GET /projects/{id}/abandon-records`（新增，可选接入）

- 方法：`GET`，路径：`/projects/{id}/abandon-records`，需要登录；
- 权限：项目发布者、当前接单领队、**曾经放弃过该项目的领队**、`ADMIN`；其他账号 **403**；
- 返回：该项目全部放弃记录，按操作时间倒序。

```json
{
  "code": 1,
  "msg": "success",
  "data": [
    {
      "id": 1,
      "projectId": 20,
      "leaderAccountId": 3,
      "reason": "临时家里有事,无法带队",
      "previousStatus": "CONFIRMED",
      "departureAt": "2026-11-10T09:00:00",
      "abandonedAt": "2026-10-10T15:30:00",
      "createdAt": "2026-10-10T15:30:00"
    }
  ]
}
```

该接口是后端为「放弃原因可审计」补的，前端不接也不影响其它功能。

## 三、`GET /projects/mine`（新增筛选参数）

```http
GET /projects/mine?relation=LEADING&stage=ACCEPTED&pageNum=1&pageSize=10
Authorization: Bearer <token>
```

| 参数 | 说明 |
| --- | --- |
| `relation` | 不变：`ALL`（默认）/ `PUBLISHED` / `LEADING` / `JOINED` |
| `status` | 单个原始状态，如 `OPEN`、`CONFIRMED`、`DONE` |
| `statuses` | 多个原始状态：重复传参 `?statuses=CONFIRMED&statuses=IN_PROGRESS` 或逗号分隔均可 |
| `stage` | 阶段筛选：`ACCEPTED` → `CONFIRMED` + `IN_PROGRESS`；`COMPLETED` → `DONE` |

- 优先级：`stage` > `statuses` > `status`（同时传时只取前一个非空的）；
- **非法值现在返回 400**（以前 `stage`、`statuses` 会被当成未知参数静默忽略）。所以前端如果传过
  `PENDING`、`ACCEPT`、`FINISHED` 之类不在枚举里的值，列表会直接报错，需要先改成 `ACCEPTED` / `COMPLETED`。
- ⚠️ 需要前端确认：`ACCEPTED` 目前**包含 `IN_PROGRESS`**（即「已接单且未结束」）。如果「已接单」页签只想显示
  `CONFIRMED`，告诉后端改映射即可（一行配置）。

## 四、前端要改的地方（清单）

1. 放弃带队：务必带上 `reason`（查询串 / JSON 体 / 表单参数任选），并把 400、403 的新文案展示给用户；
2. 「我的项目」：如果用 `stage`，只能传 `ACCEPTED` 或 `COMPLETED`；其它拼写会被 400 拒绝；
3. 「放弃」按钮的可用性建议按 6 小时规则前置判断（可本地用出发时间减去服务器时间），避免用户点了才被拒；
4. 可选：接入 `GET /projects/{id}/abandon-records` 用于展示放弃原因。

## 五、部署依赖（需要运维/后端先做）

- 本次新增表 `project_abandon_records`，脚本：`scripts/create_project_abandon_records.sql`（可重复执行）；
- **顺序：先执行脚本，再换 jar**。表不存在时放弃带队会在写审计记录时报错并整体回滚——项目数据不会被改坏，
  但接口会返回 500「系统繁忙，请稍后再试」。

## 六、复测建议

1. 放弃带队：不带 reason → 400；4 字 → 400；`?reason=` 5 字 → 通过；
2. 出发时间在 5 小时 59 分后 → 403；设置在 6 小时 01 分后 → 通过；
3. 项目没有出发时间（`departureDate`/`departureTime` 为空）→ 400「订单时间异常，请联系客服处理」；
4. 放弃成功后：`leaderAccountId` 为空、状态 `OPEN`、群聊里原领队降为普通成员、`/abandon-records` 能查到该条记录；
5. 「我的项目」：`stage=ACCEPTED` 能看到 `CONFIRMED` 与 `IN_PROGRESS`；`stage=COMPLETED` 只看 `DONE`；
   `stage=PENDING` → 400。
