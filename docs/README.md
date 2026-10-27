# 文档索引

本目录保存后端的设计、接口与联调文档。README 负责「怎么用接口」，这里补充「系统为什么这样设计」。

| 文档 | 内容 | 适合谁看 | 时效 |
|---|---|---|---|
| [architecture.md](./architecture.md) | 系统分层、部署拓扑、AI 规划与推荐链路、关键设计权衡、已知边界 | 新成员、评审、答辩 | 随架构变更维护 |
| [database.md](./database.md) | ER 图、20 张表的职责、索引与约束、迁移脚本清单 | 后端、需要造数据的同学 | 随表结构变更维护 |
| [API.md](./API.md) | 路线规划 Agent 接口契约（异步任务、事件、错误体） | 前端、Agent 接入方 | 对应 `openapi.json` |
| [openapi.json](./openapi.json) | Agent 接口的机器可读契约，可导入 Apifox / Postman / Swagger Editor | 前端、测试 | 同上 |
| [agent-api-frontend-notice.md](./agent-api-frontend-notice.md) | Agent 接口的前端接入说明与联调注意点 | 前端 | 一次性通知 |
| [project-abandon-frontend-notice.md](./project-abandon-frontend-notice.md) | 放弃带队、阶段筛选的前端变更通知 | 前端 | 一次性通知 |
| [backend-change-checklist.md](./backend-change-checklist.md) | 2026-10 的 P0 变更清单、实现位置与验收点 | 后端、测试 | 历史记录 |
| [project-wrap-up-plan.md](./project-wrap-up-plan.md) | 收尾阶段的分工与周计划 | 项目成员 | 规划，执行后归档 |

## 约定

- 面向使用者的接口说明写在根目录 `README.md`，本目录只放设计与契约类文档，避免同一份内容两处维护。
- 每份文档开头写清「更新时间 + 适用版本」，变更接口或表结构时同步修改。
- 前端联调通知用 `*-frontend-notice.md` 命名，写清「变了什么、前端要改什么、怎么验证」。

## 相关目录

- `perf/`：压测脚本与多轮结果，含 AI 路线生成优化、查询性能、推荐链路复测报告。
- `postman/`：可直接导入的接口集合与环境变量。
- `scripts/`：建库、增量迁移、数据导入与压测辅助脚本。
