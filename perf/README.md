# AI 路线生成 · 性能测试

针对后端 `POST /routes/ai/:memoryId` 接口的两条曲线：

| 曲线 | x 轴 | y 轴 | 怎么测 |
|---|---|---|---|
| ① 多用户并发 | 并发用户数 | 平均生成时间 | message 模式（不带 body，走 **生成 + 优化** 全流程） |
| ② 路线复杂程度 | 提交景点数 N | 生成时间 | body 携带 N 个景点（走 optimize 优化路径） |

## 前置条件

- 服务器地址：默认 `http://47.94.95.110:8080`（会自动重定向到 8443 HTTPS）
- 登录账号（手机号 + 密码），后端接口需要 JWT
- 正式压测：Node.js ≥ 18（无第三方依赖）
- 绘图：Python + matplotlib（可选，不装也能用 Excel 画）

## 方法 A：Postman（单次请求检查）

1. 导入 `postman/study-tour.postman_collection.json` 和配套环境 `postman/study-tour.postman_environment.json`。
2. 切到「Study Tour」环境，确认 `baseUrl`，在登录请求中填写测试账号并执行登录。
3. 选择 AI 路线生成请求，填写独立的 `memoryId` 和请求参数，执行后查看 Postman 的响应耗时。

批量并发、复杂度扫描和 CSV 统计使用下面的 Node 脚本。

## 方法 B：Node 压测（正式，多轮统计 + CSV）

```bash
# 一次跑完两条曲线
node scripts/perf-ai-route.js both --phone 13800000000 --password 123456

# 只跑某一条，可自定义档位
node scripts/perf-ai-route.js concurrency --phone ... --password ... --users 1,2,4,8,12,16 --trials 3
node scripts/perf-ai-route.js complexity  --phone ... --password ... --levels 3,6,9,12,15,18,20 --trials 3
```

参数（也可用环境变量 `PERF_PHONE / PERF_PASSWORD / PERF_BASE_URL`）：

| 参数 | 默认 | 说明 |
|---|---|---|
| `--users` | `1,2,4,8,12,16` | 并发档位 |
| `--levels` | `3,6,9,12,15,18,20` | 复杂度档位（提交的景点数） |
| `--trials` | `3` | 每个档位跑几轮 |
| `--no-warmup` | 有 warmup | 每档先跑 1 次热身再计时（建议保持） |
| `--base-url` | `http://47.94.95.110:8080` | 服务器地址 |
| `--out-dir` | `perf/` | 结果目录；建议正式测试传入独立的 `perf/runs/<run-name>`，避免覆盖历史基线 |

输出到 `perf/`：

- `concurrency_raw.csv` / `concurrency_summary.csv`
- `complexity_raw.csv` / `complexity_summary.csv`

统计列：`avg_ms / p50_ms / p95_ms / p99_ms / min_ms / max_ms / errors`。**平均只统计成功样本**（HTTP 200 且 `code==1`），失败单独计数。

## 画图

```bash
pip install matplotlib
python scripts/plot_perf.py           # 保存 perf/ai_route_perf.png
python scripts/plot_perf.py --show    # 弹窗显示
```

不想装 Python 也可以：直接用 Excel 打开两个 `*_summary.csv`，选「并发数/N」和 `avg_ms` 两列插折线图。

## 结果解读注意事项

1. **高德交通矩阵在 Redis 缓存 24h**。同一对景点的出行数据首次请求会打高德（全局 8 QPS 限流、单次 20s 超时），之后走缓存很快。第一次跑某个档位会偏慢，这就是每档先 warmup 的原因；要对比不同并发下的相对表现，建议先完整跑一遍把常用 pair 缓存填满再测。

2. **候选景点上限 20**。`optimize` 路径总候选 ≤ 20，交通矩阵 ≈ C(20,2)=190 对，**矩阵规模不随 N 明显增大**。所以复杂度曲线主要反映：提交景点越多 → prompt 越大、LLM 要输出的景点条目越多 → 生成时间随 N 增长（近似线性）。如果以后放开候选上限，矩阵会按 O(N²) 暴涨，复杂度曲线会显著变陡。

3. **全局 8 QPS 限流是并发下的天然瓶颈**。并发升高时，多个用户的矩阵请求在 `AmapTransitClient` 的限流器上排队，平均生成时间会明显上升——这正是并发曲线想看到的现象。若需要并发吞吐，瓶颈在 `app.amap.transit-qps`，可调大后重测。

4. **并发测试里每个请求用独立 `memoryId`**，避免共享 Redis 会话状态互相污染。`saveOrUpdateAIConversationRoute` 会为每次生成插入一条 route，跑完测试会多出一些测试路线，属正常现象。

5. **时间口径**：脚本记录的是客户端墙钟时间（含网络开销），即用户实际感知的生成时间。后端控制器日志里也打了 `costMs`，可 `grep "costMs"` 服务器日志做服务端口径对照。
