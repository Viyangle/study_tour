# 后端变更清单（P0：放弃带队 / 我的项目阶段筛选）

更新时间：2026-10-10（原清单：2026-10-09）

> 本文原来按当前 Android 客户端实现、`README(2).md` 接口说明与线上后端只读验证结果整理，列出线上仍有缺口的 P0 项。
> 截至 2026-10-10，**这 6 项已在本地工作区全部实现并通过测试**；线上部署版本尚未包含，
> 发布这一版后请按「验收 / 复测点」逐条确认。表中保留原始问题描述，便于对照。

## 一、变更表

| 优先级 | 类型 | 模块 | 原问题（线上观察） | 原建议变更 | 状态 | 实现位置 | 验收 / 复测点 |
|---|---|---|---|---|---|---|---|
| P0 | 修改 | `POST /projects/{id}/abandon` 时间限制 | README 只要求“出发前”；线上请求未体现 6 小时限制 | 距离开始时间至少 6 小时才能放弃，恰好 6 小时允许，使用服务器时间 | 已实现 | `ProjectServiceImpl.abandonProject`：`ABANDON_LEAD_HOURS = 6`，仅当 `now.isAfter(departure.minusHours(6))` 才拒绝 | 提前 5 小时 59 分 → 403；提前 6 小时整 → 允许；时间基准为服务器 `LocalDateTime.now()` |
| P0 | 修改 | 放弃原因 | 客户端会发送 `reason`，但后端没有必填和长度校验的证据 | `reason` 必填，去除首尾空格后限制为 5–200 字 | 已实现 | `ProjectServiceImpl.normalizeAbandonReason`；`ProjectController.abandonProject` 接受查询串、JSON 体与表单参数三种传法 | 缺失、空白、4 字、201 字 → 400；5 字与 200 字 → 通过 |
| P0 | 添加 | 放弃记录 | 成功后会恢复订单，但文档没有说明放弃原因被保存 | 新增放弃记录表，保存项目、领队、原因、原状态和操作时间 | 已实现 | `scripts/create_project_abandon_records.sql`、`ProjectAbandonRecord`、`ProjectAbandonRecordMapper(.xml)`、`GET /projects/{id}/abandon-records` | 放弃成功后能查询到完整审计记录：项目、领队、原因、放弃前状态、出发时间快照与操作时间 |
| P0 | 修改 | 放弃事务 | 会清除领队、恢复 `OPEN` 并移除群聊领队身份，但未确认四步在同一事务中 | 将时间校验、原因记录、状态恢复和群聊移除放在同一事务中 | 已实现 | `abandonProject` 标注 `@Transactional`：`selectByIdForUpdate`（行锁）→ `casAbandonProject` → 写审计记录 → `chatService.removeProjectLeader` | 任一步失败时全部回滚（项目仍为 `CONFIRMED` 且保留原领队），不出现半完成状态；并发放弃只成功一次 |
| P0 | 修改 | 异常时间 | 规则未定义时间缺失或格式异常时的行为 | `departureDate/departureTime` 缺失或非法时拒绝操作，返回“订单时间异常，请联系客服处理” | 已实现 | `ProjectServiceImpl.requireDepartureForAbandon` | 时间为空 → 400 且返回该文案；不得使用默认时间继续执行 |
| P0 | 添加 | `/projects/mine` 阶段筛选 | 线上只支持单个原始 `status`；`stage/statuses` 会被静默忽略 | 增加 `stage=ACCEPTED/COMPLETED` | 已实现 | `ProjectController.getMyProjects`（新增 `stage` 参数）、`ProjectServiceImpl.resolveMyProjectStatuses` + `MY_PROJECT_STAGES`、`ProjectMapper.selectByAccountRelation`（`status in (...)`） | `stage=ACCEPTED` → `CONFIRMED/IN_PROGRESS`；`stage=COMPLETED` → `DONE`；非法阶段或非法 `statuses` 值 → 400，不再静默忽略；优先级 `stage` > `statuses` > `status` |

## 二、对客户端可见的行为变化

1. `POST /projects/{id}/abandon` 现在必须携带放弃原因，查询串 `?reason=xxx`、JSON 体 `{"reason":"xxx"}`、
   表单参数 `reason=xxx` 三种传法都支持，缺失即 400。
2. 该接口新增拒绝条件：距出发不足 6 小时 → 403；项目状态不是 `CONFIRMED` → 403；出发时间缺失 → 400。
3. 新增 `GET /projects/{id}/abandon-records`（需登录）。项目发布者、当前领队、曾放弃的领队与 `ADMIN` 可查询，其它账号 403。
4. `GET /projects/mine` 新增 `stage` 与 `statuses` 两个参数。以前它们会被当作未知参数静默忽略，
   现在 `statuses` 会被真正解析（去重后作为 `status in (...)`），`stage` / `statuses` 的非法枚举值返回 400。
   `status` 的非法枚举值一直是 400，未变。客户端若存在拼写错误或传了其它枚举值（例如 `PENDING`），需要先修正。
5. 这些接口的错误响应仍是既有 `{code,msg,data}` 格式（`code=0` + `msg`），与 `/routes/agent/**`、`/health` 的裸 JSON 不同。

## 三、配套数据库脚本

- `scripts/create_project_abandon_records.sql`：新建 `project_abandon_records`（`CREATE TABLE IF NOT EXISTS`，可重复执行；外键指向 `projects`、`accounts`）。
- `scripts/full_schema.sql`（开发用整库脚本）同步补上了同一张表（同样的 `CREATE TABLE IF NOT EXISTS` 定义），建库时不再漏表。
- 线上库若尚未建表，必须在发布前执行：表不存在时 `insert` 会失败并整笔回滚，接口表现为 500「系统繁忙，请稍后再试」，项目状态不会被改动。

## 四、验证记录

- 2026-10-11 提交前回归：`mvn -o '-Dtest=*,!StudyTourApplicationTests' test` → **205 项通过，0 失败、0 错误**（BUILD SUCCESS）。未执行依赖真实 MySQL + Redis 的 `StudyTourApplicationTests.contextLoads`。
- 2026-10-10：`mvn -o '-Dtest=*,!StudyTourApplicationTests' test` → **197 项通过，0 失败、0 错误**（BUILD SUCCESS）。
- 已自动覆盖（`ProjectServiceImplTest` 既有 2 项 + 本轮新增 21 项）：
  - 放弃成功路径：行锁校验 → CAS → 审计记录写入（项目、领队、原因、放弃前状态、出发时间快照、操作时间）→ 群聊领队移除
  - 6 小时边界：提前 5 小时 59 分 → 403 且无任何副作用；6 小时（留 5 秒余量，判据是 `now.isAfter(departure-6h)`）→ 允许；已出发 → 403
  - `reason`：缺失 / 空白 / 4 字 / 201 字 → 400；5 字 / 200 字 → 通过；首尾空格被去除后入库
  - 出发时间：`departureDate` 或 `departureTime` 缺失 → 400 且文案为“订单时间异常，请联系客服处理”，不写审计记录
  - 状态与并发：状态非 `CONFIRMED` → 403；CAS 抢失败（并发改状态）→ 403，不写审计记录、不移群聊领队
  - `/projects/{id}/abandon-records` 权限矩阵：发布者 / 当前领队 / 曾放弃的前领队 / `ADMIN` 可见，无关账号 403
  - `/projects/mine`：`stage=ACCEPTED` → `CONFIRMED`/`IN_PROGRESS`、`stage=COMPLETED` → `DONE`、大小写不敏感、优先级 `stage` > `statuses` > `status`、`statuses` 去重、非法 `stage` / 非法 `statuses` → 400、无筛选时不加状态约束、分页字段正确
  - 新增测试类：`ProjectAbandonRulesTest`（13 项）、`ProjectMyProjectsFilterTest`（8 项）
- 尚未自动覆盖，建议发布后手测：
  - 接口级回归：上述校验经真实 Spring 过滤链后的 HTTP 400/403 状态码与 `{code,msg,data}` 文案
  - 真实 MySQL 下的行锁与 CAS 并发（两个领队同时放弃只成功一次）
  - `/projects/{id}/abandon-records` 在真实库上的排序（`abandoned_at desc, id desc`）
- 数据库脚本已在本地 MySQL 8.0.43 上实跑验证（临时 schema，验证后已删除，未触碰 `study_tour` 库）：
  `scripts/create_project_abandon_records.sql` 与 `scripts/full_schema.sql` 新增段落都能成功建表；
  按 mapper 的列顺序实测 `insert`（`useGeneratedKeys` 自增 ID 正常）与 `select ... order by abandoned_at desc, id desc` 正常；
  外键生效——写入不存在的 `project_id` 报 1452，因此租户数据不会串。
- 仍未在真实库上执行过的是：把这张表建到线上/本地 `study_tour` 库（需要你自己决定执行时机）。
- 全部测试使用 mock，未连接真实 MySQL、Redis、模型与高德；未执行依赖真实 MySQL + Redis 的 `StudyTourApplicationTests.contextLoads`。

## 五、遗留与后续

- **发布顺序**：先执行 `scripts/create_project_abandon_records.sql`，再替换 jar。
- 面向前端的联调通知见 [project-abandon-frontend-notice.md](./project-abandon-frontend-notice.md)。
- 新增边界测试集中在 `src/test/java/com/viyangle/study_tour/service/impl/ProjectAbandonRulesTest.java` 与 `ProjectMyProjectsFilterTest.java`，改规则时同步维护。
- README 已按本次实现同步：领队端「放弃带队」、`4.2.10 领队放弃带队并重新开放项目`、`4.2.11 查询放弃带队记录`、`我的项目` 的筛选参数。
- `BACKEND_TODO.md` 第 2、3 项（AI 请求容量控制、并发与幂等）仍为待办，本清单不涉及。
