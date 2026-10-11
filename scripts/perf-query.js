#!/usr/bin/env node
/**
 * 研学项目查询接口负载测试（Node.js >= 18，无第三方依赖）
 *
 * 场景：
 *   projects            首页项目推荐：内存知识图谱 + 排序
 *   projects-filter     项目筛选页：MySQL 分页查询
 *   projects-explained  首页推荐及推荐理由
 *   routes              路线推荐列表
 *   attractions         景点分页列表
 *   mix                 按权重混合以上场景
 *
 * 模式：
 *   smoke        每个目标端点请求一次，只验证环境
 *   concurrency  逐档固定时长加压，绘制并发—延迟—吞吐曲线
 *   sustained    固定并发持续运行，验证稳定性
 */
"use strict";

const fs = require("fs");
const http = require("http");
const https = require("https");
const os = require("os");
const path = require("path");
const { performance } = require("perf_hooks");

const ENDPOINTS = [
  "projects",
  "projects-filter",
  "projects-explained",
  "routes",
  "attractions",
];
const SCENARIOS = [...ENDPOINTS, "mix"];
const MODES = ["smoke", "concurrency", "sustained"];
const MIX_WEIGHTS = {
  projects: 50,
  "projects-filter": 20,
  "projects-explained": 10,
  routes: 15,
  attractions: 5,
};
const MAX_REDIRECTS = 3;

function parseArgs(argv) {
  const parsed = {};
  for (let i = 0; i < argv.length; i++) {
    const value = argv[i];
    if (!value.startsWith("--")) {
      parsed._mode = parsed._mode || value;
      continue;
    }
    const key = value.slice(2);
    const next = argv[i + 1];
    if (next !== undefined && !next.startsWith("--")) {
      parsed[key] = next;
      i++;
    } else {
      parsed[key] = true;
    }
  }
  return parsed;
}

function numberArg(value, fallback, name, { integer = false, min = 0 } = {}) {
  if (value === undefined || value === "") return fallback;
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < min || (integer && !Number.isInteger(parsed))) {
    throw new Error(`参数 --${name} 无效: ${value}`);
  }
  return parsed;
}

function numberListArg(value, fallback, name) {
  if (value === undefined || value === "") return fallback;
  const parts = String(value).split(",");
  if (parts.some((item) => !item.trim())) {
    throw new Error(`参数 --${name} 含有空项: ${value}`);
  }
  const result = parts.map((item) =>
    numberArg(item.trim(), null, name, { integer: true, min: 1 })
  );
  if (!result.length) throw new Error(`参数 --${name} 不能为空`);
  return result;
}

function pageRangeArg(value) {
  if (!value) return [1, 3];
  const values = numberListArg(value, null, "page-range");
  if (values.length !== 2 || values[0] > values[1]) {
    throw new Error("--page-range 应为 min,max，且 min <= max");
  }
  return values;
}

function truthyEnv(value) {
  return ["1", "true", "yes", "on"].includes(String(value || "").toLowerCase());
}

function runStamp(date = new Date()) {
  return date.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
}

function buildConfig(args) {
  const mode = args._mode || "concurrency";
  if (!MODES.includes(mode)) {
    throw new Error(`未知模式 ${mode}，可选: ${MODES.join(" / ")}`);
  }
  const scenario = args.endpoint || process.env.PERF_ENDPOINT || "projects";
  if (!SCENARIOS.includes(scenario)) {
    throw new Error(`未知端点 ${scenario}，可选: ${SCENARIOS.join(" / ")}`);
  }

  const defaultOutDir = path.resolve(
    __dirname,
    "../perf/query-runs",
    `${runStamp()}-${mode}-${scenario}`
  );
  const cfg = {
    mode,
    scenario,
    baseUrl: String(args["base-url"] || process.env.PERF_BASE_URL || "http://localhost:8080")
      .replace(/\/+$/, ""),
    phone: String(args.phone || process.env.PERF_PHONE || ""),
    password: String(args.password || process.env.PERF_PASSWORD || ""),
    token: String(args.token || process.env.PERF_TOKEN || ""),
    accountId: String(args["account-id"] || process.env.PERF_ACCOUNT_ID || ""),
    concurrencyLevels: numberListArg(args.users, [1, 2, 4, 8, 16, 32, 64, 128], "users"),
    durationPerLevelSeconds: numberArg(
      args["duration-per-level"],
      15,
      "duration-per-level",
      { min: 1 }
    ),
    sustainedConcurrency: numberArg(args.concurrency, 32, "concurrency", {
      integer: true,
      min: 1,
    }),
    sustainedDurationSeconds: numberArg(args.duration, 60, "duration", { min: 1 }),
    warmupSeconds: args["no-warmup"] !== undefined
      ? 0
      : numberArg(args["warmup-seconds"], 2, "warmup-seconds", { min: 0 }),
    cooldownMs: numberArg(args["cooldown-ms"], 1500, "cooldown-ms", {
      integer: true,
      min: 0,
    }),
    requestTimeoutMs: numberArg(args["timeout-ms"], 30000, "timeout-ms", {
      integer: true,
      min: 100,
    }),
    pageSize: numberArg(args["page-size"], 10, "page-size", { integer: true, min: 1 }),
    pageRange: pageRangeArg(args["page-range"]),
    filterKeyword: String(args["filter-keyword"] || ""),
    filterRegionCode: String(args["filter-region"] || "320100"),
    filterTag: String(args["filter-tag"] || ""),
    filterStatus: String(args["filter-status"] || "OPEN"),
    onlyAvailable: args["include-full"] === undefined,
    insecure: args.insecure !== undefined || truthyEnv(process.env.PERF_INSECURE),
    failOnError: args["fail-on-error"] !== undefined,
    outputDir: path.resolve(String(args["out-dir"] || defaultOutDir)),
  };

  let parsedBase;
  try {
    parsedBase = new URL(cfg.baseUrl);
  } catch {
    throw new Error(`--base-url 不是合法 URL: ${cfg.baseUrl}`);
  }
  if (!["http:", "https:"].includes(parsedBase.protocol)) {
    throw new Error("--base-url 只支持 http:// 或 https://");
  }
  return cfg;
}

function printHelp() {
  console.log(`
用法:
  node scripts/perf-query.js smoke [参数]
  node scripts/perf-query.js concurrency [参数]
  node scripts/perf-query.js sustained [参数]

核心参数:
  --base-url URL             默认 http://localhost:8080
  --phone PHONE              登录手机号（或环境变量 PERF_PHONE）
  --password PASSWORD        登录密码（或环境变量 PERF_PASSWORD）
  --token JWT                跳过登录；可搭配 --account-id
  --endpoint NAME            ${SCENARIOS.join(" | ")}
  --page-range MIN,MAX       默认 1,3
  --page-size N              默认 10

并发阶梯:
  --users LIST               默认 1,2,4,8,16,32,64,128
  --duration-per-level SEC   每档计时 15 秒
  --warmup-seconds SEC       每档预热 2 秒；--no-warmup 可关闭
  --cooldown-ms MS           档位间冷却 1500 ms

持续负载:
  --concurrency N            默认 32
  --duration SEC             默认 60 秒

筛选参数:
  --filter-keyword TEXT      默认空；传“文物”等可测 LIKE 模糊查询
  --filter-region CODE       默认 320100
  --filter-tag TAG           默认空
  --filter-status STATUS     默认 OPEN
  --include-full             不附加 onlyAvailable=true

其他:
  --timeout-ms MS            单请求超时，默认 30000
  --insecure                 允许自签 HTTPS 证书（仅测试环境）
  --out-dir DIR              输出目录；默认每次创建带时间戳的目录
  --fail-on-error            只要有请求失败，就令进程退出码为 2
`);
}

function createContext(cfg) {
  return {
    cfg,
    agents: {
      http: new http.Agent({ keepAlive: true, maxSockets: Infinity, maxFreeSockets: 256 }),
      https: new https.Agent({
        keepAlive: true,
        maxSockets: Infinity,
        maxFreeSockets: 256,
        rejectUnauthorized: !cfg.insecure,
      }),
    },
  };
}

function closeContext(ctx) {
  ctx.agents.http.destroy();
  ctx.agents.https.destroy();
}

function request(ctx, method, url, { token, body } = {}) {
  return new Promise((resolve, reject) => {
    let redirects = 0;

    const perform = (currentUrl) => {
      const parsed = new URL(currentUrl);
      const transport = parsed.protocol === "https:" ? https : http;
      const payload = body == null
        ? null
        : typeof body === "string"
          ? body
          : JSON.stringify(body);
      const headers = {
        Accept: "application/json",
        ...(payload
          ? {
              "Content-Type": "application/json",
              "Content-Length": Buffer.byteLength(payload),
            }
          : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      };

      const req = transport.request(
        parsed,
        {
          method,
          headers,
          agent: parsed.protocol === "https:" ? ctx.agents.https : ctx.agents.http,
          timeout: ctx.cfg.requestTimeoutMs,
        },
        (res) => {
          if (
            [301, 302, 303, 307, 308].includes(res.statusCode) &&
            res.headers.location &&
            redirects < MAX_REDIRECTS
          ) {
            res.resume();
            redirects++;
            perform(new URL(res.headers.location, parsed).toString());
            return;
          }
          let data = "";
          res.setEncoding("utf8");
          res.on("data", (chunk) => {
            data += chunk;
          });
          res.on("end", () => {
            let json = null;
            try {
              json = JSON.parse(data);
            } catch {
              // 非 JSON 响应会作为失败样本保留。
            }
            resolve({ status: res.statusCode, headers: res.headers, data, json });
          });
        }
      );
      req.on("timeout", () => req.destroy(new Error("request timeout")));
      req.on("error", reject);
      if (payload) req.write(payload);
      req.end();
    };

    perform(url);
  });
}

function accountIdFromToken(token) {
  try {
    const payload = token.split(".")[1];
    const decoded = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
    return decoded.accountId != null
      ? String(decoded.accountId)
      : decoded.sub != null
        ? String(decoded.sub)
        : "";
  } catch {
    return "";
  }
}

async function authenticate(ctx) {
  const cfg = ctx.cfg;
  if (cfg.token) {
    const accountId = cfg.accountId || accountIdFromToken(cfg.token);
    if (!accountId) {
      throw new Error("使用 --token 时无法解析 accountId，请补充 --account-id");
    }
    console.log(`认证: 使用已有 token，accountId=${accountId}`);
    return { token: cfg.token, accountId };
  }
  if (!cfg.phone || !cfg.password) {
    throw new Error(
      "缺少认证信息。请传 --phone/--password，或通过 PERF_PHONE/PERF_PASSWORD 设置。"
    );
  }
  const response = await request(ctx, "POST", `${cfg.baseUrl}/login`, {
    body: { phone: cfg.phone, password: cfg.password },
  });
  if (response.status !== 200 || !response.json || response.json.code !== 1) {
    throw new Error(
      `登录失败: HTTP ${response.status}, ${String(response.data).slice(0, 200)}`
    );
  }
  const token = response.json.data && response.json.data.token;
  const account = response.json.data && response.json.data.account;
  if (!token || !account || account.id == null) {
    throw new Error("登录响应缺少 token 或 account.id");
  }
  console.log(`认证: 登录成功，accountId=${account.id}`);
  return { token, accountId: String(account.id) };
}

function randomPage(cfg) {
  const [min, max] = cfg.pageRange;
  return min + Math.floor(Math.random() * (max - min + 1));
}

function buildQueryUrl(cfg, auth, endpoint) {
  let pathname;
  if (["projects", "projects-explained"].includes(endpoint)) pathname = "/projects";
  else if (endpoint === "projects-filter") pathname = "/projects/filter";
  else if (endpoint === "routes") pathname = "/routes";
  else if (endpoint === "attractions") pathname = "/attractions";
  else throw new Error(`不支持的端点: ${endpoint}`);

  const target = new URL(`${cfg.baseUrl}${pathname}`);
  target.searchParams.set("pageNum", String(randomPage(cfg)));
  target.searchParams.set("pageSize", String(cfg.pageSize));

  if (["projects", "projects-explained", "projects-filter", "routes"].includes(endpoint)) {
    target.searchParams.set("accountId", auth.accountId);
  }
  if (endpoint === "projects-explained") {
    target.searchParams.set("withExplanation", "true");
  }
  if (endpoint === "projects-filter") {
    if (cfg.filterKeyword) target.searchParams.set("keyword", cfg.filterKeyword);
    if (cfg.filterRegionCode) target.searchParams.set("regionCode", cfg.filterRegionCode);
    if (cfg.filterTag) target.searchParams.set("tag", cfg.filterTag);
    if (cfg.filterStatus) target.searchParams.set("status", cfg.filterStatus);
    if (cfg.onlyAvailable) target.searchParams.set("onlyAvailable", "true");
  }
  if (endpoint === "attractions" && cfg.filterRegionCode) {
    target.searchParams.set("regionCode", cfg.filterRegionCode);
  }
  return target.toString();
}

function chooseMixedEndpoint() {
  const entries = Object.entries(MIX_WEIGHTS);
  const total = entries.reduce((sum, [, weight]) => sum + weight, 0);
  let cursor = Math.random() * total;
  for (const [endpoint, weight] of entries) {
    cursor -= weight;
    if (cursor <= 0) return endpoint;
  }
  return entries[entries.length - 1][0];
}

async function executeQuery(ctx, auth, scenario, worker, sequence) {
  const endpoint = scenario === "mix" ? chooseMixedEndpoint() : scenario;
  const url = buildQueryUrl(ctx.cfg, auth, endpoint);
  const startedAt = new Date().toISOString();
  const start = performance.now();
  try {
    const response = await request(ctx, "GET", url, { token: auth.token });
    const elapsedMs = performance.now() - start;
    const ok = response.status === 200 && response.json && response.json.code === 1;
    const businessMessage = response.json && response.json.msg;
    return {
      started_at: startedAt,
      scenario,
      endpoint,
      worker,
      sequence,
      ms: round(elapsedMs, 3),
      ok,
      status: response.status,
      code: response.json ? response.json.code : "",
      bytes: Buffer.byteLength(response.data || "", "utf8"),
      error: ok
        ? ""
        : businessMessage || `HTTP ${response.status}: ${String(response.data).slice(0, 160)}`,
    };
  } catch (error) {
    return {
      started_at: startedAt,
      scenario,
      endpoint,
      worker,
      sequence,
      ms: round(performance.now() - start, 3),
      ok: false,
      status: "",
      code: "",
      bytes: 0,
      error: error && error.message ? error.message : String(error),
    };
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function runTimedPhase(ctx, auth, scenario, concurrency, durationSeconds) {
  const results = [];
  let sequence = 0;
  const started = performance.now();
  const deadline = started + durationSeconds * 1000;

  const worker = async (workerId) => {
    while (performance.now() < deadline) {
      sequence++;
      results.push(await executeQuery(ctx, auth, scenario, workerId, sequence));
    }
  };

  await Promise.all(
    Array.from({ length: concurrency }, (_, index) => worker(index + 1))
  );
  return { results, wallMs: performance.now() - started };
}

function percentile(sorted, ratio) {
  if (!sorted.length) return 0;
  const index = Math.max(0, Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1));
  return sorted[index];
}

function round(value, digits = 3) {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

function summarize(results, details, wallMs) {
  const successes = results.filter((item) => item.ok);
  const latencies = successes.map((item) => item.ms).sort((a, b) => a - b);
  const total = results.length;
  const errors = total - successes.length;
  const latencySum = latencies.reduce((sum, value) => sum + value, 0);
  const wallSeconds = Math.max(wallMs / 1000, 0.001);
  return {
    ...details,
    duration_s: round(wallSeconds, 3),
    total,
    ok: successes.length,
    errors,
    error_rate_pct: round(total ? (errors / total) * 100 : 0, 4),
    avg_ms: round(latencies.length ? latencySum / latencies.length : 0, 3),
    p50_ms: round(percentile(latencies, 0.5), 3),
    p90_ms: round(percentile(latencies, 0.9), 3),
    p95_ms: round(percentile(latencies, 0.95), 3),
    p99_ms: round(percentile(latencies, 0.99), 3),
    min_ms: round(latencies[0] || 0, 3),
    max_ms: round(latencies[latencies.length - 1] || 0, 3),
    throughput_req_s: round(total / wallSeconds, 3),
    bytes_avg: round(
      successes.length
        ? successes.reduce((sum, item) => sum + item.bytes, 0) / successes.length
        : 0,
      1
    ),
  };
}

function phaseSummaries(results, cfg, concurrency, wallMs) {
  const common = {
    mode: cfg.mode,
    scenario: cfg.scenario,
    concurrency,
  };
  const rows = [
    summarize(
      results,
      { ...common, scope: "overall", endpoint: cfg.scenario },
      wallMs
    ),
  ];
  if (cfg.scenario === "mix") {
    for (const endpoint of ENDPOINTS) {
      const samples = results.filter((item) => item.endpoint === endpoint);
      if (samples.length) {
        rows.push(
          summarize(
            samples,
            { ...common, scope: "endpoint", endpoint },
            wallMs
          )
        );
      }
    }
  }
  return rows;
}

function csvEscape(value) {
  if (value === null || value === undefined) return "";
  const text = String(value);
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

function writeCsv(file, rows, columns) {
  const lines = [columns.join(",")];
  for (const row of rows) {
    lines.push(columns.map((column) => csvEscape(row[column])).join(","));
  }
  fs.writeFileSync(file, `\ufeff${lines.join("\r\n")}`, "utf8");
}

function publicConfig(cfg) {
  return {
    mode: cfg.mode,
    scenario: cfg.scenario,
    baseUrl: cfg.baseUrl,
    concurrencyLevels: cfg.concurrencyLevels,
    durationPerLevelSeconds: cfg.durationPerLevelSeconds,
    sustainedConcurrency: cfg.sustainedConcurrency,
    sustainedDurationSeconds: cfg.sustainedDurationSeconds,
    warmupSeconds: cfg.warmupSeconds,
    cooldownMs: cfg.cooldownMs,
    requestTimeoutMs: cfg.requestTimeoutMs,
    pageSize: cfg.pageSize,
    pageRange: cfg.pageRange,
    filterKeyword: cfg.filterKeyword,
    filterRegionCode: cfg.filterRegionCode,
    filterTag: cfg.filterTag,
    filterStatus: cfg.filterStatus,
    onlyAvailable: cfg.onlyAvailable,
    insecure: cfg.insecure,
    mixWeights: cfg.scenario === "mix" ? MIX_WEIGHTS : undefined,
  };
}

function persist(outputDir, rawRows, summaryRows, metadata) {
  fs.mkdirSync(outputDir, { recursive: true });
  writeCsv(path.join(outputDir, "query_raw.csv"), rawRows, [
    "started_at",
    "mode",
    "scenario",
    "endpoint",
    "concurrency",
    "worker",
    "sequence",
    "ms",
    "ok",
    "status",
    "code",
    "bytes",
    "error",
  ]);
  writeCsv(path.join(outputDir, "query_summary.csv"), summaryRows, [
    "mode",
    "scenario",
    "scope",
    "endpoint",
    "concurrency",
    "duration_s",
    "total",
    "ok",
    "errors",
    "error_rate_pct",
    "avg_ms",
    "p50_ms",
    "p90_ms",
    "p95_ms",
    "p99_ms",
    "min_ms",
    "max_ms",
    "throughput_req_s",
    "bytes_avg",
  ]);
  fs.writeFileSync(
    path.join(outputDir, "query_meta.json"),
    `${JSON.stringify(metadata, null, 2)}\n`,
    "utf8"
  );
}

function tagRawRows(rows, mode, concurrency) {
  return rows.map((row) => ({ ...row, mode, concurrency }));
}

function printSummary(row) {
  console.log(
    `  样本=${row.total} 成功=${row.ok} 错误率=${row.error_rate_pct.toFixed(2)}% ` +
      `avg=${row.avg_ms.toFixed(1)}ms p95=${row.p95_ms.toFixed(1)}ms ` +
      `p99=${row.p99_ms.toFixed(1)}ms 吞吐=${row.throughput_req_s.toFixed(1)} req/s`
  );
}

async function runSmoke(ctx, auth, rawRows, summaryRows) {
  const targets = ctx.cfg.scenario === "mix" ? ENDPOINTS : [ctx.cfg.scenario];
  for (const endpoint of targets) {
    const started = performance.now();
    const sample = await executeQuery(ctx, auth, endpoint, 1, 1);
    const wallMs = performance.now() - started;
    rawRows.push(...tagRawRows([sample], "smoke", 1));
    summaryRows.push(
      summarize(
        [sample],
        {
          mode: "smoke",
          scenario: ctx.cfg.scenario,
          scope: "endpoint",
          endpoint,
          concurrency: 1,
        },
        wallMs
      )
    );
    console.log(
      `  ${endpoint.padEnd(20)} ${sample.ok ? "OK" : "FAIL"} ` +
        `${sample.ms.toFixed(1)}ms HTTP=${sample.status || "-"} code=${sample.code || "-"}`
    );
  }
}

async function runConcurrency(ctx, auth, rawRows, summaryRows, metadata) {
  for (const concurrency of ctx.cfg.concurrencyLevels) {
    if (ctx.cfg.warmupSeconds > 0) {
      process.stdout.write(
        `并发 ${String(concurrency).padStart(3)}：预热 ${ctx.cfg.warmupSeconds}s ... `
      );
      const warmup = await runTimedPhase(
        ctx,
        auth,
        ctx.cfg.scenario,
        concurrency,
        ctx.cfg.warmupSeconds
      );
      const failures = warmup.results.filter((item) => !item.ok).length;
      console.log(`完成（${warmup.results.length} 请求，${failures} 失败）`);
    }

    console.log(
      `并发 ${String(concurrency).padStart(3)}：计时 ${ctx.cfg.durationPerLevelSeconds}s`
    );
    const phase = await runTimedPhase(
      ctx,
      auth,
      ctx.cfg.scenario,
      concurrency,
      ctx.cfg.durationPerLevelSeconds
    );
    rawRows.push(...tagRawRows(phase.results, "concurrency", concurrency));
    const summaries = phaseSummaries(phase.results, ctx.cfg, concurrency, phase.wallMs);
    summaryRows.push(...summaries);
    printSummary(summaries[0]);

    metadata.finishedAt = new Date().toISOString();
    persist(ctx.cfg.outputDir, rawRows, summaryRows, metadata);
    if (ctx.cfg.cooldownMs > 0 && concurrency !== ctx.cfg.concurrencyLevels.at(-1)) {
      await sleep(ctx.cfg.cooldownMs);
    }
  }
}

async function runSustained(ctx, auth, rawRows, summaryRows) {
  const concurrency = ctx.cfg.sustainedConcurrency;
  if (ctx.cfg.warmupSeconds > 0) {
    console.log(`预热：${concurrency} 并发 x ${ctx.cfg.warmupSeconds}s`);
    await runTimedPhase(ctx, auth, ctx.cfg.scenario, concurrency, ctx.cfg.warmupSeconds);
  }
  console.log(`持续负载：${concurrency} 并发 x ${ctx.cfg.sustainedDurationSeconds}s`);
  const phase = await runTimedPhase(
    ctx,
    auth,
    ctx.cfg.scenario,
    concurrency,
    ctx.cfg.sustainedDurationSeconds
  );
  rawRows.push(...tagRawRows(phase.results, "sustained", concurrency));
  const summaries = phaseSummaries(phase.results, ctx.cfg, concurrency, phase.wallMs);
  summaryRows.push(...summaries);
  printSummary(summaries[0]);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help !== undefined || args.h !== undefined) {
    printHelp();
    return;
  }

  const cfg = buildConfig(args);
  const ctx = createContext(cfg);
  const rawRows = [];
  const summaryRows = [];
  const metadata = {
    runId: `query-${runStamp()}`,
    startedAt: new Date().toISOString(),
    finishedAt: null,
    config: publicConfig(cfg),
    loadGenerator: {
      node: process.version,
      platform: process.platform,
      arch: process.arch,
      cpuLogicalCores: os.cpus().length,
      hostname: os.hostname(),
    },
  };

  console.log(`目标: ${cfg.baseUrl}`);
  console.log(`模式: ${cfg.mode}，场景: ${cfg.scenario}`);
  console.log(`输出: ${cfg.outputDir}`);

  try {
    const auth = await authenticate(ctx);
    if (cfg.mode === "smoke") {
      await runSmoke(ctx, auth, rawRows, summaryRows);
    } else if (cfg.mode === "sustained") {
      await runSustained(ctx, auth, rawRows, summaryRows);
    } else {
      await runConcurrency(ctx, auth, rawRows, summaryRows, metadata);
    }
    metadata.finishedAt = new Date().toISOString();
    persist(cfg.outputDir, rawRows, summaryRows, metadata);
    console.log(`完成，结果已保存到 ${cfg.outputDir}`);

    const errors = rawRows.filter((item) => !item.ok).length;
    if (cfg.failOnError && errors > 0) process.exitCode = 2;
  } finally {
    closeContext(ctx);
  }
}

main().catch((error) => {
  console.error(`错误: ${error && error.message ? error.message : error}`);
  if (process.env.DEBUG && error && error.stack) console.error(error.stack);
  process.exitCode = 1;
});
