# 贡献指南

本文约定本仓库的开发流程、提交规范与质量门禁。改动代码前请先读一遍，能省掉大部分返工。

## 1. 环境准备

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | `pom.xml` 的 `java.version`；更高版本可编译，但 CI 固定 17 |
| Maven | 3.9+ | CI 使用 `mvn -B -ntp -Pci verify` |
| MySQL | 8.0 | 本地建库脚本见 `scripts/full_schema.sql` |
| Redis | 6+ | 缓存与会话；向量检索需要 Redis Stack（RediSearch） |

配置项通过环境变量注入，清单见 [.env.example](.env.example)。本机开发可以沿用 `application.yaml` 里的默认值，生产环境必须覆盖 `DB_PASSWORD`、`JWT_SECRET` 与 `SSL_KEY_STORE_PASSWORD`。

## 2. 分支与提交

- `main` 保持可构建、可演示；功能开发走 `feat/<主题>`、缺陷修复走 `fix/<主题>` 分支，合并前需 CI 通过。
- 提交信息采用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：
  - `feat: 新增路线收藏接口`
  - `fix: 修正放弃带队的时间边界判断`
  - `test: 补充知识图谱推荐用例`
  - `docs:` / `chore:` / `refactor:` / `perf:`
- 一个提交只做一件事；格式化、重命名与逻辑修改不要混在一起，方便回滚与评审。

## 3. 代码约定

- 分层保持 `controller → service → mapper`，跨层调用不要跳过 service；`pojo` 只做数据载体。
- 新的请求对象优先复用已有 DTO，不要在 controller 里直接解析 `Map`。
- 外部依赖（模型、高德、OSS）必须设置超时与失败降级，异常在 `GlobalExceptionHandler` 统一转换，不要在 controller 里 `try/catch` 后返回裸字符串。
- 涉及并发状态流转的写操作，使用行锁 + CAS（参考 `ProjectServiceImpl.abandonProject`），不要只做「先查再改」。
- 新增配置项一律写成 `${ENV:默认值}` 形式，并同步到 `.env.example`。

## 4. 测试与门禁

```bash
mvn -B -ntp -Pci verify            # 与 CI 一致，排除 requires-infra 分组
mvn -B -ntp test                   # 本机已启动 MySQL / Redis 时执行全量
mvn -B -ntp -Pci test -Dtest=类名   # 只跑某个测试类
```

- 新增业务逻辑必须带单元测试；修复缺陷先补一个能复现的用例。
- 覆盖率门禁在 `pom.xml` 的 `jacoco-maven-plugin`：行 ≥ 39%、分支 ≥ 31%（棘轮阈值，只允许上调）。补测试后请同步上调阈值，避免覆盖率回退。
- 只依赖外部服务的用例统一打 `@Tag("requires-infra")`，保证 CI 在无数据库环境下可执行。
- 测试命名建议 `方法_场景_预期`，断言要说明业务含义，不要只断言不抛异常。

## 5. 数据库变更

数据库结构由 Flyway 管理，脚本在 `src/main/resources/db/migration`：

- 新增变更：新建 `V<版本>__<snake_case 描述>.sql`，版本号递增；**不要修改已执行过的脚本**（校验和会失败，应用启动报错）。
- 存量库：首次启动自动 baseline 到 V1，无需手工执行历史脚本；空库会从 V1 建表，再执行后续增量。
- 本地验证：`mvn -B -ntp test -Dtest=FlywayMigrationTest`（需要本机 MySQL，连接信息读 `DB_*` 环境变量）。
- `scripts/` 下的历史脚本与 `full_schema.sql` 只用于追溯和带演示数据的本地库，不再作为新环境的建库入口。
- 数据类脚本（`seed_*`）不属于结构迁移，按需手工执行。
- 涉及外键/索引的变更，先在本地 MySQL 8 上实跑验证，再提交。

## 6. 文档同步

- 接口签名、参数、错误码变化 → 同步 `README.md` 对应章节，并通知前端（参考 `docs/*-frontend-notice.md` 的写法）。
- 架构或模块职责变化 → 更新 `docs/architecture.md`。
- 表结构变化 → 更新 `docs/database.md`。
- 面向使用者的行为变化 → 在 `CHANGELOG.md` 的 `Unreleased` 下记一条。

## 7. 提交前检查清单

- [ ] `mvn -B -ntp -Pci verify` 通过
- [ ] 新增/修改的逻辑有对应测试
- [ ] 配置项通过环境变量注入，无新增硬编码密钥
- [ ] 数据库变更使用新的 Flyway 脚本，并跑过 `FlywayMigrationTest`
- [ ] README / docs / CHANGELOG 已按影响面更新
