# 路线规划 Agent 接口 · 前端联调通知

更新日期：2026-10-10
适用范围：`docs/API.md` + `docs/openapi.json` 描述的 Agent 契约
字段级约束以 `docs/openapi.json` 为准，本文件只说明接入方式、与原型文档的差异，以及需要前端配合的改动。

> 一句话结论：Agent 已经实现在**现有后端**里（不是独立服务），与现有接口共用同一个 `BASE_URL`
> 和同一套 access token，不需要再配 `AGENT_AUTH_URL`。

## 一、端点

| 方法 | 路径 | 用途 | 成功状态 |
| --- | --- | --- | --- |
| POST | `/routes/agent/runs` | 创建一轮异步规划 | 202 |
| GET | `/routes/agent/runs/{runId}` | 查询阶段、事件与结果 | 200 |
| DELETE | `/routes/agent/runs/{runId}` | 取消规划 | 200 |
| GET | `/health` | 服务健康检查，无需登录 | 200 |

鉴权与身份：

- 前三个接口必须带 `Authorization: Bearer <access token>`；
- 请求体里的 `accountId` 必须等于 token 所属账号，否则 403；
- 查询、取消其他账号的任务与「任务不存在」统一返回 404，不要当重试信号；
- 不要在请求里放模型供应商 Key 或高德 Key，这些都在服务端配置。

## 二、响应格式（与现有接口不同，务必分开处理）

- `/routes/agent/**` 与 `/health` **直接返回 JSON 对象**，不包 `{code,msg,data}`；
- 拒绝类错误体固定为：

| HTTP | 响应体 | 场景 |
| --- | --- | --- |
| 401 | `{"error": "..."}` | 未登录、token 无效 |
| 403 | `{"error": "..."}` | `accountId` 与登录身份不一致 |
| 404 | `{"error": "..."}` | 任务不存在、过期、属于其他账号 |
| 409 | `{"error": "..."}` | 同 requestId 不同内容，或同会话已有活跃任务 |
| 422 | `{"detail": [{"type","loc","msg","input","ctx"}]}` | 缺少字段、格式/范围错误、未知字段、currentRoute 重复 poiId |
| 429 | `{"error": "..."}` | 新建过于频繁、全局并发或保存任务数达上限 |

- 执行期失败**不是** HTTP 错误：`GET` 返回 200 + `status=failed` + `error`（如模型不支持工具调用、地图鉴权失败、整轮超时）；
- 仓库其他接口仍保持 `{code,msg,data}`，两套格式按路径区分。

## 三、202 不等于成功

- 202 只表示任务已接受，不能据此提示「路线生成成功」；
- 必须轮询 `GET`（建议 1.8～2 秒一次，`status` 进入终态后停止），事件列表是**当前快照**，不是增量流；
- `status`：`queued` / `running` / `completed` / `failed` / `cancelled`；
- `completed` 后再看 `result.kind`：
  - `plan`：方案。`requiresAcceptance` 恒为 `true`，确认后再应用，**不要自动覆盖**客户端当前路线；
  - `clarification`：追问（≤3 个问题）。这是「成功完成的一轮」，不是失败；
    用户回答后用**同一个 `memoryId`、新的 `requestId`** 再建一轮，把历史对话和本轮回答带上，
    最新回答只在 `message` 里放一次。
- `stage` 是给人看的展示文案，**不要**用它做枚举判断。

## 四、契约要点

| 项 | 行为 |
| --- | --- |
| `runId` | 32 位小写十六进制，和原后端的整数 `routeId` 不是一回事，不要混用 |
| 幂等 | 同账号 + 同 `requestId` + 同请求体重复提交 → 202 且返回已有任务，不重复规划 |
| 幂等冲突 | 同 `requestId` 不同请求体 → 409 |
| 会话互斥 | 同账号同 `memoryId` 同时只允许一个活跃任务，否则 409 |
| 限流 | 单账号 60 秒内最多新建 6 次；全局活跃任务最多 8 个；保存任务上限 500 条；超出 429。幂等重复不占新建额度 |
| 任务保留 | 从创建时刻算 30 分钟；运行中不清理，终态记录在后续请求时清理 |
| 取消 | `DELETE` 会释放会话锁，但运行中的任务可能先返回 `status=running`（取消在检查点生效，已在执行的模型/地图调用可能仍完成）；已完成/失败/取消的任务返回现有终态，不要假设一定是 `cancelled` |
| 结果落库 | 方案不自动落库，发布仍走原有 `/routes/manual` 和发布接口 |

## 五、需要前端配合的 5 处改动

1. **`memoryId` ≤ 128 字符**。文档原先写 160，后端会话键上限是 128，超出直接 422
   （`type=string_too_long`，`loc=["body","memoryId"]`）。
2. **`visitTime` 格式转换**。方案节点形如 `"2026-10-11 09:00"` 或 `"第1天 09:00"`；
   调用 `POST /routes/manual` 时请转成 ISO `"2026-10-11T09:00:00"`，该接口仍只接受 ISO，后端未放宽。
3. **先同步 POI 再应用**。`POST /routes/manual` 按 `poiId` 查 `attractions` 表，库里没有会 400；
   方案里出现库里没有的高德 POI 时，先调 `POST /attractions/sync/{poiId}`。
4. **不要按原型文档的工具名判断**。实际事件只有：
   无 `tool` 的「理解需求」、`search_places`、`get_place_details`、`calculate_leg`、`build_itinerary`、`finish_plan`、`ask_user`；
   不会出现 `get_weather`、`use_current_location`、`search_nearby`、`set_constraints`、`optimize_order`。
5. **费用字段按估算展示**。`estimatedCost` 目前只统计高德能给出的已知交通费用（通常为 0），
   `unknownCosts` 会把每个未核价的地点和交通段计入；`warnings` 会出现「有 N 项费用未核实」
   「某段为直线估算」等提示，必须展示，不能当零费用承诺。
   硬约束（必去地点、每天时长、最晚结束时间、步行上限）无法满足时返回 `result.kind=clarification` 追问，
   不会返回 `feasible=false` 的方案（契约中 `feasible` 恒为 `true`、`issues` 恒为空数组）。

## 六、不要做的事

- 不要把 `memoryId` 和账号命名空间自行拼接（后端已按登录账号隔离）；
- 不要在 `history` 里塞临时进度、网络错误或模拟的工具结果；`role` 只接受 `user` / `assistant`
  （`system`、`tool` 会 422）；
- 不要把 202 当成功、不要把事件失败当整轮失败、不要用 `stage` 做判断。

## 附：部署前提与冒烟清单（给部署的人）

部署务必满足：

- 配置 `AMAP_KEY`（高德 **Web 服务** Key，不能用 Android Key）、`CHAT_MODEL_KEY`（需支持工具调用的模型）、
  `JWT_SECRET`、`SSL_KEY_STORE_PASSWORD`，以及可用的 **MySQL + Redis**；
- Redis 同时承担 AI 会话状态、高德路径缓存与向量索引。`app.rag.embedding.store-enabled=true` 时需要
  Redis Stack(RediSearch)，否则召回退化为兜底候选池；
- **单实例部署**：任务、限流、会话锁都在进程内存，重启丢任务，多 worker 随机分发会破坏幂等与互斥；
- `/health` 挂在服务根路径且免登录，确认网关/nginx 没有占用或改写该路径；
  Agent 路径的 401/403 是裸 `{"error":...}`，若网关会改写错误体需要放行；
- 运行限制可在 `application.yaml` 的 `app.agent.*` 调整（整轮超时 240s、任务保留 30m、保存上限 500、
  全局活跃 8、单账号 60s/6 次、事件上限 110、后台线程 4、单轮高德请求上限 90）；
  高德路径规划端点与超时/QPS/缓存见 `app.amap.direction-*`、`app.amap.walking-endpoint`、`app.amap.driving-endpoint`。

部署后按顺序冒烟（第一次走真实模型与高德）：

1. `GET /health` → `{"status":"ok","version":"1.0"}`
2. 不带 token 调 `POST /routes/agent/runs` → 401 + `{"error":"未登录或token无效"}`
3. 带 token 但 `accountId` 写错 → 403
4. 正常创建 → 202 + `runId`；轮询 `GET` 直到 `completed`，检查 `result.kind`
5. 同 `requestId` 同请求体重发 → 202 且 `runId` 不变
6. 新建一轮后 `DELETE` → 观察是否 `cancelled`
7. 换第二个账号查第一个账号的 `runId` → 404

本次后端验证记录：

- 离线编译通过；`mvn -o '-Dtest=*,!StudyTourApplicationTests' test` → 176 项通过，0 失败 0 错误；
- `application.yaml` 已解析校验；
- 未执行真实模型/高德/线上库调用，全部测试为 mock 或本地桩；
- `StudyTourApplicationTests.contextLoads` 需要本机 MySQL + Redis，沿用仓库原有的排除方式。
