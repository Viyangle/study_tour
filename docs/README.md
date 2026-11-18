# 文档索引

本目录保存后端的设计、接口与联调文档。README 负责「怎么用接口」，这里补充「系统为什么这样设计」。

| 文档 | 内容 | 适合谁看 | 时效 |
|---|---|---|---|
| [architecture.md](./architecture.md) | 系统分层、部署拓扑、AI 规划与推荐链路、关键设计权衡、已知边界 | 新成员、评审、答辩 | 随架构变更维护 |
| [attraction-guide-design.md](./attraction-guide-design.md) | 景点研学讲解表、来源链、防错配规则、自动同步、AI 降级与运维入口 | 后端、前端、评审 | 与 V7/V8 迁移同步维护 |
| [deployment.md](./deployment.md) | 远端服务、数据库迁移、备份、发布、回滚、当前部署记录与已知问题 | 后端、运维 | 随部署变化维护 |
| [attraction-tag-vector-design.md](./attraction-tag-vector-design.md) | 景点标签从 15 选 1 升级为 15 维向量、反馈采集与在线校准的完整方案 | 后端、前端、评审 | 方案定稿；向量、自动打分、匹配打分、查询接口、离线评估、反馈闭环、PPR/MMR、多标签路线与项目软筛已实现 |
| [tag-feedback-v2-design.md](./tag-feedback-v2-design.md) | 反馈 2.0：把语义先验、全局学习层、会话/用户偏好拆开，修正「用户选择 ≠ 景点 tag 匹配」 | 后端、算法、评审 | 核心数据/学习层/排序模式/pairwise 训练工具已实现；灰度未做 |
| [database.md](./database.md) | ER 图、30 张表的职责、索引与约束、迁移脚本清单 | 后端、需要造数据的同学 | 随表结构变更维护 |
| [API.md](./API.md) | 路线规划 Agent 接口契约（异步任务、事件、错误体） | 前端、Agent 接入方 | 对应 `openapi.json` |
| [openapi.json](./openapi.json) | Agent 接口的机器可读契约，可导入 Apifox / Postman / Swagger Editor | 前端、测试 | 同上 |
| [agent-api-frontend-notice.md](./agent-api-frontend-notice.md) | Agent 接口的前端接入说明与联调注意点 | 前端 | 一次性通知 |
| [project-abandon-frontend-notice.md](./project-abandon-frontend-notice.md) | 放弃带队、阶段筛选的前端变更通知 | 前端 | 一次性通知 |
| [attraction-guide-frontend-notice.md](./attraction-guide-frontend-notice.md) | 景点研学讲解接口的前端接入、字段可空性、来源/降级展示和联调清单 | 前端 | 2026-11-18 接口已上线 |
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
