# 项目查询与并发性能测试

这套脚本面向导师汇报中的“千级项目数据下，后端查询性能如何”问题。测试将首页推荐链路和直接数据库查询链路分开，避免把知识图谱计算耗时误认为 MySQL 查询耗时。

## 一、数据集

执行 [seed_nanjing_projects.sql](../scripts/seed_nanjing_projects.sql) 后会新增：

| 数据 | 数量 | 设计 |
|---|---:|---|
| 项目 | 1,200 | 30 个主题 × 8 种研学形式 × 5 类参与群体 |
| 路线 | 240 | 每条路线关联 3 个数据库内已有的南京真实景点 |
| 普通用户 | 180 | 自然中文姓名，每人 3 个兴趣偏好 |
| 领队 | 24 | 含领队资料、评分和接单关系 |
| 项目成员 | 约 5,400 | 每项目 2～8 个实际参与人数，形成协同过滤关系 |

项目标题示例：

- `明城墙脉络与都城格局深度研学·亲子共学营`
- `玄武湖湿地与候鸟观察任务挑战·初中生探究团`
- `非遗技艺与匠人精神观察工作坊·大学生实践行`
- `校园博物馆与学科发现沉浸体验·教师研修班`

状态按真实列表需要分布：OPEN 480、MATCHING 240、CONFIRMED 240、IN_PROGRESS 60、DONE 120、CANCELLED 60。15 类业务标签全部覆盖。

脚本是可重复执行的：它只刷新手机号 `188760` / `188761` 开头账号所拥有的合成项目，并清理由旧版脚本产生的 `南京测试项目xx`，不会清空整个业务库。若已有业务项目手工复用了合成路线，该路线会被保留。

### 填充本地数据库

项目根目录下执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\seed-project-performance.ps1
```

该命令默认从 `src/main/resources/application.yaml` 读取本地 MySQL 用户和密码，也可临时通过 `STUDY_TOUR_DB_USER`、`STUDY_TOUR_DB_PASSWORD` 覆盖。SQL 使用事务和数量断言；核心数据不足时会报错并回滚。

若在 Linux 服务器直接执行 SQL：

```bash
mysql --default-character-set=utf8mb4 -u root -p study_tour < scripts/seed_nanjing_projects.sql
```

同步数据库后必须重启后端服务。首页推荐依赖启动时加载的内存知识图谱；不重启时，新增项目不会进入图数据，测试结果会失真。

填充生成的登录账号统一使用密码 `123456`。建议压测账号：

- 手机号：`18876100001`
- 密码：`123456`

## 二、测试对象与口径

| 场景参数 | 接口 | 实际主要工作 | 报告中的称呼 |
|---|---|---|---|
| `projects` | `GET /projects` | 遍历项目、个性化 PageRank、MMR 排序 | 首页推荐链路 |
| `projects-filter` | `GET /projects/filter` | MySQL 条件过滤、排序和分页 | 数据库项目查询 |
| `projects-explained` | `GET /projects?withExplanation=true` | 推荐及理由组装 | 带解释推荐链路 |
| `routes` | `GET /routes` | 路线偏好排序和分页 | 路线列表查询 |
| `attractions` | `GET /attractions` | 景点地区过滤和分页 | 景点列表查询 |
| `mix` | 按 50/20/10/15/5 权重混合 | 模拟首页及列表页组合流量 | 综合读场景 |

主要指标：

- `avg`：成功请求平均响应时间。
- `P95 / P99`：95% / 99% 成功请求不超过的响应时间，比平均值更能反映卡顿。
- `throughput_req_s`：该阶段总请求数除以实际墙钟时间，包含失败请求。
- `error_rate_pct`：HTTP 非 200、业务 `code != 1`、超时或连接错误占比。
- 并发数表示同时在途的请求数，不等于系统注册用户总数。

每个并发档位会持续运行一段时间，一个工作协程在上个请求完成后立即发送下一个请求。因此每档会收集足量样本，P95/P99 比“每档只发三次”的结果可靠。

## 三、推荐执行顺序

Node.js 需为 18 或更高版本，脚本无第三方依赖。以下命令均在项目根目录运行。

PowerShell 中可先设置认证信息，避免每条命令重复输入：

```powershell
$env:PERF_PHONE = "18876100001"
$env:PERF_PASSWORD = "123456"
```

### 1. Smoke：确认环境，不计入报告

本地服务若从 HTTP 重定向到自签名 HTTPS，需要加 `--insecure`：

```powershell
node scripts\perf-query.js smoke --endpoint mix --base-url http://localhost:8080 --insecure
```

5 个端点均应显示 `OK`、HTTP 200、业务码 1。服务器证书受信任时不要加 `--insecure`。

### 2. 数据库查询基准

先测 `/projects/filter`，它是最接近“项目列表数据库查询”的链路：

```powershell
node scripts\perf-query.js concurrency --endpoint projects-filter --base-url https://服务器地址:8443 --users 1,2,4,8,16,32,64,128 --duration-per-level 30 --page-range 1,5
```

上述默认条件为 `regionCode=320100`、`status=OPEN`、`onlyAvailable=true`。另跑一轮带前导通配符的关键字搜索，展示复杂查询代价：

```powershell
node scripts\perf-query.js concurrency --endpoint projects-filter --base-url https://服务器地址:8443 --users 1,2,4,8,16,32,64,128 --duration-per-level 30 --page-range 1,5 --filter-keyword 文物
```

### 3. 首页项目推荐基准

```powershell
node scripts\perf-query.js concurrency --endpoint projects --base-url https://服务器地址:8443 --users 1,2,4,8,16,32,64,128 --duration-per-level 30 --page-range 1,5
```

这轮会覆盖知识图谱项目遍历、个性化 PageRank 与 MMR；它反映用户实际看到首页列表的端到端耗时，但不能直接称为“数据库 SQL 耗时”。

### 4. 综合持续负载

```powershell
node scripts\perf-query.js sustained --endpoint mix --base-url https://服务器地址:8443 --concurrency 32 --duration 300 --page-range 1,5
```

5 分钟持续负载用于观察错误率、延迟漂移、连接池等待和 JVM 稳定性。第一次正式测试可从 60 秒开始，确认安全后再跑 300 秒。

### 5. 快速试跑

正式测试前可用较短参数检查脚本：

```powershell
node scripts\perf-query.js concurrency --endpoint projects-filter --users 1,4 --duration-per-level 3 --warmup-seconds 1 --base-url http://localhost:8080 --insecure
```

## 四、输出与绘图

每次运行自动创建独立目录，不会覆盖历史结果：

```text
perf/query-runs/20260826T...-concurrency-projects-filter/
├── query_raw.csv       每个请求的时间、端点、耗时、状态、响应大小和错误
├── query_summary.csv   每档 avg/P50/P90/P95/P99/吞吐/错误率
└── query_meta.json     本次参数和发压机环境，不含密码或 token
```

混合场景的 `query_summary.csv` 同时包含：

- `scope=overall`：整套混合流量的总指标，用于主报告。
- `scope=endpoint`：每个子端点在同一阶段内的诊断指标，用于定位瓶颈。

绘制最新一次并发曲线：

```powershell
python scripts\plot_query_perf.py
```

或指定某次结果：

```powershell
python scripts\plot_query_perf.py --dir perf\query-runs\某次运行目录
```

输出图片保存在相同运行目录中的 `query_perf.png`；持续负载还会输出 `query_sustained_perf.png`。绘图需要 `matplotlib`，CSV 本身可直接用 Excel 打开。

## 五、测试纪律与汇报建议

为保证结果可复现：

1. 发压机尽量与服务端分离，并记录 CPU、内存、JDK、MySQL 版本和网络位置。
2. 正式跑数前重启一次后端，完成 smoke 和预热后再计时；各轮保持相同 JVM、连接池和数据库配置。
3. 每个并发档至少持续 30 秒，正式数据重复 3 轮，报告中给出中位轮或三轮均值。
4. 测试期间不要同时运行 AI 路线生成、数据库同步等重任务。
5. 同时观察服务端 CPU/内存、GC、Tomcat 线程、Druid 活跃连接/等待数，以及 MySQL 慢查询。
6. 压测只能对测试环境或明确授权的服务器执行；先从低并发逐档上升，出现持续错误后停止继续加压。

导师汇报可按以下结构组织：

1. 数据条件：项目总数 1234，其中合成项目 1200；说明状态和标签分布。
2. 数据库链路：给出 `projects-filter` 在 1/8/32/64/128 并发下的 P95、吞吐和错误率。
3. 首页链路：给出 `projects` 的同口径数据，并说明额外包含知识图谱计算。
4. 系统拐点：指出吞吐不再增长或 P95 明显上升的第一个并发档。
5. 稳定性：给出 32 并发持续 5 分钟的总请求数、P95、P99 和错误率。
6. 结论使用限定语，例如“在本次硬件、网络、1234 个项目及当前配置下”。
