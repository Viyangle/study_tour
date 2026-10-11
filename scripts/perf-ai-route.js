#!/usr/bin/env node
/**
 * AI 路线生成性能压测驱动
 *
 * 对应接口：POST {baseUrl}/routes/ai/{memoryId}
 *   ① 并发曲线：message 模式（不带 body，触发 生成+优化 全流程）
 *   ② 复杂度曲线：body 携带 N 个景点（走 optimize 优化路径），N 越大路线越复杂
 *
 * 用法（任选其一，均可配环境变量）：
 *   node perf-ai-route.js both          --phone 13800000000 --password 123456
 *   node perf-ai-route.js concurrency   --phone 13800000000 --password 123456 --users 1,2,4,8,12,16 --trials 3
 *   node perf-ai-route.js complexity    --phone 13800000000 --password 123456 --levels 3,6,9,12,15,18,20 --trials 3
 *   node perf-ai-route.js message       --phone 13800000000 --password 123456   # 用户需求复杂度(1/3/6/10句话)
 *   node perf-ai-route.js concurrency   --phone ... --password ... --out-dir perf/runs/run-001
 *
 * 环境变量：PERF_PHONE / PERF_PASSWORD / PERF_BASE_URL（默认 http://47.94.95.110:8080）
 *
 * 输出：
 *   perf/concurrency_raw.csv      每一发请求的明细（ms 级）
 *   perf/concurrency_summary.csv  按并发数汇总（avg/p50/p95/p99/max）
 *   perf/complexity_raw.csv       同上（按景点数 N）
 *   perf/complexity_summary.csv
 *
 * 绘图：python scripts/plot_perf.py
 *
 * 说明：
 *   - 高德交通矩阵结果在 Redis 缓存 24h，首次请求会走高德（全局 8 QPS 限流），
 *     之后命中缓存会快很多。每个档位默认先跑 1 次 warmup（--no-warmup 关闭）。
 *   - 并发模式下每次请求用独立 memoryId，避免共享 Redis 会话状态。
 *   - 摘要统计只统计成功(HTTP 200 且 code==1)样本；失败单独计数。
 *   - 纯 Node >= 18，无第三方依赖。
 */
"use strict";

const http = require("http");
const https = require("https");
const fs = require("fs");
const path = require("path");

// ---------------------------------------------------------------------------
// 配置区（命令行参数会覆盖）
// ---------------------------------------------------------------------------
const CONFIG = {
  baseUrl: process.env.PERF_BASE_URL || "http://47.94.95.110:8080",
  phone: process.env.PERF_PHONE || "",
  password: process.env.PERF_PASSWORD || "",

  // 并发曲线档位：并发用户数
  concurrencyLevels: [1, 2, 4, 8, 12, 16],
  // 每个并发档位跑几轮
  concurrencyTrials: 3,
  // 轮与轮之间的静默间隔(ms)，让服务端缓冲
  betweenRoundsDelayMs: 2000,

  // 复杂度曲线档位：提交的景点数 N
  complexityLevels: [3, 6, 9, 12, 15, 18, 20],
  complexityTrials: 3,
  // 复杂度曲线跑在多少并发下（默认 1 = 单用户）
  complexityUsers: 1,
  // 拉取候选景点的地区（南京）
  complexityRegionCode: "320100",

  // message 复杂度曲线档位：1/3/6/10 句话，逐级追加约束（走 message 生成全流程）
  messageLevels: [
    {
      sentences: 1,
      message:
        "我要在南京进行两天的研学旅行，帮我规划一条历史人文主题的路线。",
    },
    {
      sentences: 3,
      message:
        "我要在南京进行两天的研学旅行，帮我规划一条历史人文主题的路线。第一天想多看几个博物馆，第二天安排得轻松一点。希望路线尽量顺路，不要走回头路。",
    },
    {
      sentences: 6,
      message:
        "我要在南京进行两天的研学旅行，帮我规划一条历史人文主题的路线。第一天想多看几个博物馆，第二天安排得轻松一点。希望路线尽量顺路，不要走回头路。不要南京城墙，我已经去过了。每天下午四点半以后不要安排游览，需要留出休息时间。中午十二点左右要留出吃饭的时间，景点之间尽量有地铁或公交直达。",
    },
    {
      sentences: 10,
      message:
        "我要在南京进行两天的研学旅行，帮我规划一条历史人文主题的路线。第一天想多看几个博物馆，第二天安排得轻松一点。希望路线尽量顺路，不要走回头路。不要南京城墙，我已经去过了。每天下午四点半以后不要安排游览，需要留出休息时间。中午十二点左右要留出吃饭的时间，景点之间尽量有地铁或公交直达。我带着一个十岁的孩子，每个景点停留时间不要超过两个小时，节奏要慢一些。第一天晚上住在夫子庙附近，希望第二天最后一个景点离住处不要太远。这几天天气比较热，尽量多安排室内景点，减少室外暴晒。如果有博物馆研学或非遗体验的项目也欢迎推荐，可以适当增加体验类活动。",
    },
  ],
  messageTrials: 2,

  // message 模式（并发曲线）用的用户需求
  generationMessage:
    "我要在南京，2026年3月20日开始的两天内进行历史方面的旅游，请给我规划一个路线",

  // 每个档位先 warmup 一次再计时
  warmup: true,
  // 单请求超时(ms)，LLM 生成可能很慢
  requestTimeoutMs: 600000,
};

const DEFAULT_OUT_DIR = path.resolve(__dirname, "../perf");
const MAX_REDIRECTS = 3;

// ---------------------------------------------------------------------------
// 简易参数解析
// ---------------------------------------------------------------------------
function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next !== undefined && !next.startsWith("--")) {
        args[key] = next;
        i++;
      } else {
        args[key] = true;
      }
    } else {
      args._cmd = args._cmd || a;
    }
  }
  return args;
}

// ---------------------------------------------------------------------------
// HTTP 小工具：跟随 8080->8443 重定向，忽略自签证书
// ---------------------------------------------------------------------------
function request(method, url, { headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    let redirects = 0;
    const doRequest = (curUrl) => {
      const u = new URL(curUrl);
      const lib = u.protocol === "https:" ? https : http;
      const payload =
        body === undefined || body === null
          ? null
          : typeof body === "string"
            ? body
            : JSON.stringify(body);

      const options = {
        method,
        headers: {
          "Content-Type": "application/json",
          ...(payload ? { "Content-Length": Buffer.byteLength(payload) } : {}),
          ...headers,
        },
        // 自签证书（8080 重定向到 8443 时必然出现），压测场景关闭校验
        ...(u.protocol === "https:" ? { rejectUnauthorized: false } : {}),
        timeout: CONFIG.requestTimeoutMs,
      };

      const req = lib.request(u, options, (res) => {
        if (
          [301, 302, 303, 307, 308].includes(res.statusCode) &&
          res.headers.location &&
          redirects < MAX_REDIRECTS
        ) {
          res.resume();
          redirects++;
          doRequest(new URL(res.headers.location, u).toString());
          return;
        }
        let data = "";
        res.setEncoding("utf8");
        res.on("data", (chunk) => (data += chunk));
        res.on("end", () => {
          let json = null;
          try {
            json = JSON.parse(data);
          } catch {
            /* 非 JSON 响应，保留原始文本 */
          }
          resolve({ status: res.statusCode, data, json });
        });
      });
      req.on("timeout", () => req.destroy(new Error("request timeout")));
      req.on("error", (err) =>
        reject(new Error(`请求失败 ${curUrl}: ${err.message}`))
      );
      if (payload) req.write(payload);
      req.end();
    };
    doRequest(url);
  });
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

// ---------------------------------------------------------------------------
// 认证
// ---------------------------------------------------------------------------
async function login(baseUrl, phone, password) {
  const res = await request("POST", `${baseUrl}/login`, {
    body: { phone, password },
  });
  if (!res.json || res.json.code !== 1) {
    throw new Error(
      `登录失败: HTTP ${res.status}, 响应: ${res.data.slice(0, 200)}` +
        "\n请检查 PERF_PHONE / PERF_PASSWORD 或 --phone/--password 是否正确。"
    );
  }
  const d = res.json.data;
  return { token: d.token, accountId: d.account.id };
}

// ---------------------------------------------------------------------------
// 业务请求
// ---------------------------------------------------------------------------
const headers = (token) => ({ Authorization: `Bearer ${token}` });

/** message 模式：AI 生成 + 优化全流程（对应"用户点一次 AI 生成"） */
async function generateRoute(baseUrl, token, accountId, memoryId, message) {
  const query = `message=${encodeURIComponent(message)}&accountId=${accountId}`;
  const t0 = Date.now();
  const res = await request(
    "POST",
    `${baseUrl}/routes/ai/${memoryId}?${query}`,
    { headers: headers(token) }
  );
  const ms = Date.now() - t0;
  const ok = res.status === 200 && res.json && res.json.code === 1;
  return { ok, ms, status: res.status, code: res.json ? res.json.code : null };
}

/** optimize 模式：提交 N 个景点，让 AI 优化该路线 */
async function optimizeRoute(
  baseUrl,
  token,
  memoryId,
  message,
  attractions
) {
  const query = `message=${encodeURIComponent(message)}`;
  const t0 = Date.now();
  const res = await request(
    "POST",
    `${baseUrl}/routes/ai/${memoryId}?${query}`,
    { headers: headers(token), body: attractions }
  );
  const ms = Date.now() - t0;
  const ok = res.status === 200 && res.json && res.json.code === 1;
  return { ok, ms, status: res.status, code: res.json ? res.json.code : null };
}

/** 拉取候选景点（取地区内已收录景点） */
async function fetchAttractions(baseUrl, token, regionCode, size) {
  const res = await request(
    "GET",
    `${baseUrl}/attractions?regionCode=${encodeURIComponent(
      regionCode
    )}&pageNum=1&pageSize=${size}`,
    { headers: headers(token) }
  );
  if (!res.json || res.json.code !== 1) {
    throw new Error(`获取景点列表失败: ${res.data.slice(0, 200)}`);
  }
  const list = Array.isArray(res.json.data) ? res.json.data : [];
  const valid = list.filter(
    (a) => a && a.poiId && a.location && a.name
  );
  const seen = new Set();
  return valid.filter((a) => {
    const k = String(a.poiId).trim().toUpperCase();
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

/** 用前 N 个景点构造 RouteAttraction[] 请求体 */
function buildAttractionsBody(attractions, n) {
  return attractions.slice(0, n).map((a, i) => {
    const startHour = 9 + (i % 9); // 9~17 点错开
    return {
      poiId: a.poiId,
      visitOrder: i + 1,
      visitTime: `2026-04-01T${String(startHour).padStart(2, "0")}:00:00`,
      recommendedDuration: 90,
      notes: "",
      name: a.name,
      location: a.location,
      adcode: a.adcode || "",
      citycode: a.citycode || "",
    };
  });
}

// ---------------------------------------------------------------------------
// 并发曲线
// ---------------------------------------------------------------------------
async function runConcurrencyLevel(
  baseUrl,
  token,
  accountId,
  users,
  message,
  round,
  tag
) {
  const results = [];
  const fired = Array.from({ length: users }, (_, i) => {
    const memoryId = `perf-${tag}-${Date.now()}-${round}-${i}-${Math.random()
      .toString(36)
      .slice(2, 8)}`;
    return generateRoute(baseUrl, token, accountId, memoryId, message);
  });
  const settled = await Promise.allSettled(fired);
  settled.forEach((s) => {
    if (s.status === "fulfilled") results.push(s.value);
    else
      results.push({
        ok: false,
        ms: CONFIG.requestTimeoutMs,
        status: null,
        code: null,
        error: s.reason ? s.reason.message : "unknown",
      });
  });
  return results;
}

async function runConcurrency(cfg, token, accountId, outFile, summaryFile) {
  const message = cfg.generationMessage;
  const rawRows = [];
  const summaryRows = [];

  for (const users of cfg.concurrencyLevels) {
    const wall0 = Date.now();

    if (cfg.warmup) {
      process.stdout.write(`  热身 并发=${users} ... `);
      const warm = await runConcurrencyLevel(
        cfg.baseUrl, token, accountId, users, message, "warm", "w"
      );
      const ok = warm.filter((r) => r.ok).length;
      console.log(`完成(成功 ${ok}/${users})`);
      await sleep(cfg.betweenRoundsDelayMs);
    }

    const timed0 = Date.now();
    const allSamples = [];
    for (let round = 1; round <= cfg.concurrencyTrials; round++) {
      process.stdout.write(
        `  第${round}/${cfg.concurrencyTrials}轮 并发=${users} ... `
      );
      const samples = await runConcurrencyLevel(
        cfg.baseUrl, token, accountId, users, message, round, "c"
      );
      for (const s of samples) {
        rawRows.push({
          concurrency: users,
          round,
          ms: s.ms,
          ok: s.ok,
          status: s.status,
          code: s.code,
          error: s.error || "",
        });
      }
      allSamples.push(...samples);
      const ok = samples.filter((s) => s.ok).length;
      const avg = avgMs(samples);
      console.log(`完成 成功=${ok}/${samples.length} 平均=${fmt(avg)}`);
      if (round < cfg.concurrencyTrials)
        await sleep(cfg.betweenRoundsDelayMs);
    }

    const wallMs = Date.now() - wall0;
    // 只计计时轮（不含热身、不含轮间静默）
    const timedWallMs =
      Date.now() - timed0 - (cfg.concurrencyTrials - 1) * cfg.betweenRoundsDelayMs;
    const good = allSamples.filter((s) => s.ok);
    const summary = summarize(good, {
      concurrency: users,
      trials: cfg.concurrencyTrials,
      errors: allSamples.length - good.length,
      wall_ms: wallMs,
    });
    summaryRows.push(summary);
    console.log(
      `  => 并发=${users}: avg=${fmt(summary.avg_ms)} p50=${fmt(
        summary.p50_ms
      )} p95=${fmt(summary.p95_ms)} max=${fmt(summary.max_ms)} ` +
        `错误=${summary.errors} 吞吐≈${(summary.samples / (timedWallMs / 60000)).toFixed(1)} req/min`
    );
  }

  writeCsv(outFile, rawRows, [
    "concurrency", "round", "ms", "ok", "status", "code", "error",
  ]);
  writeCsv(summaryFile, summaryRows, [
    "concurrency", "trials", "samples", "avg_ms", "p50_ms", "p95_ms",
    "p99_ms", "min_ms", "max_ms", "errors", "wall_ms",
  ]);
  console.log(`已写入 ${path.relative(process.cwd(), outFile)} / ${path.relative(process.cwd(), summaryFile)}`);
}

// ---------------------------------------------------------------------------
// 复杂度曲线
// ---------------------------------------------------------------------------
async function runComplexityLevel(
  baseUrl,
  token,
  message,
  attractions,
  concurrency,
  round,
  tag
) {
  const results = [];
  const fired = Array.from({ length: Math.max(1, concurrency) }, (_, i) => {
    const memoryId = `perf-${tag}-${Date.now()}-${round}-${i}-${Math.random()
      .toString(36)
      .slice(2, 8)}`;
    return optimizeRoute(baseUrl, token, memoryId, message, attractions);
  });
  const settled = await Promise.allSettled(fired);
  settled.forEach((s) => {
    if (s.status === "fulfilled") results.push(s.value);
    else
      results.push({
        ok: false,
        ms: CONFIG.requestTimeoutMs,
        status: null,
        code: null,
        error: s.reason ? s.reason.message : "unknown",
      });
  });
  return results;
}

async function runComplexity(cfg, token, outFile, summaryFile) {
  const message = "优先减少通勤，并结合开放时间合理安排游览时间";
  console.log(`拉取地区 ${cfg.complexityRegionCode} 的候选景点 ...`);
  const attractions = await fetchAttractions(
    cfg.baseUrl, token, cfg.complexityRegionCode, 100
  );
  console.log(`可用景点 ${attractions.length} 个`);

  const rawRows = [];
  const summaryRows = [];

  for (const n of cfg.complexityLevels) {
    if (attractions.length < n) {
      console.log(`  景点不足，跳过 N=${n}（仅 ${attractions.length} 个可用）`);
      continue;
    }
    const body = buildAttractionsBody(attractions, n);
    const wall0 = Date.now();

    if (cfg.warmup) {
      process.stdout.write(`  热身 N=${n} ... `);
      const warm = await runComplexityLevel(
        cfg.baseUrl, token, message, body, cfg.complexityUsers, "warm", "w"
      );
      const ok = warm.filter((r) => r.ok).length;
      console.log(`完成(成功 ${ok}/${warm.length})`);
      await sleep(cfg.betweenRoundsDelayMs);
    }

    const timed0 = Date.now();
    const allSamples = [];
    for (let round = 1; round <= cfg.complexityTrials; round++) {
      process.stdout.write(`  第${round}/${cfg.complexityTrials}轮 N=${n} ... `);
      const samples = await runComplexityLevel(
        cfg.baseUrl, token, message, body, cfg.complexityUsers, round, "cm"
      );
      for (const s of samples) {
        rawRows.push({
          n,
          round,
          ms: s.ms,
          ok: s.ok,
          status: s.status,
          code: s.code,
          error: s.error || "",
        });
      }
      allSamples.push(...samples);
      const ok = samples.filter((s) => s.ok).length;
      const avg = avgMs(samples);
      console.log(`完成 成功=${ok}/${samples.length} 平均=${fmt(avg)}`);
      if (round < cfg.complexityTrials)
        await sleep(cfg.betweenRoundsDelayMs);
    }

    const wallMs = Date.now() - wall0;
    const timedWallMs =
      Date.now() - timed0 - (cfg.complexityTrials - 1) * cfg.betweenRoundsDelayMs;
    const good = allSamples.filter((s) => s.ok);
    const summary = summarize(good, {
      n,
      trials: cfg.complexityTrials,
      errors: allSamples.length - good.length,
      wall_ms: wallMs,
    });
    summaryRows.push(summary);
    console.log(
      `  => N=${n}: avg=${fmt(summary.avg_ms)} p50=${fmt(summary.p50_ms)} p95=${fmt(
        summary.p95_ms
      )} max=${fmt(summary.max_ms)} 错误=${summary.errors} ` +
        `吞吐≈${(summary.samples / (timedWallMs / 60000)).toFixed(1)} req/min`
    );
  }

  writeCsv(outFile, rawRows, ["n", "round", "ms", "ok", "status", "code", "error"]);
  writeCsv(summaryFile, summaryRows, [
    "n", "trials", "samples", "avg_ms", "p50_ms", "p95_ms", "p99_ms",
    "min_ms", "max_ms", "errors", "wall_ms",
  ]);
  console.log(`已写入 ${path.relative(process.cwd(), outFile)} / ${path.relative(process.cwd(), summaryFile)}`);
}

// ---------------------------------------------------------------------------
// message 复杂度曲线：单用户，逐级增加用户需求（1/3/6/10 句话）
// ---------------------------------------------------------------------------
async function runMessageComplexity(cfg, token, accountId, outFile, summaryFile) {
  const rawRows = [];
  const summaryRows = [];

  for (const lv of cfg.messageLevels) {
    const wall0 = Date.now();
    const message = lv.message;

    if (cfg.warmup) {
      process.stdout.write(`  热身 句数=${lv.sentences} ... `);
      const warm = await runConcurrencyLevel(
        cfg.baseUrl, token, accountId, 1, message, "warm", "msg"
      );
      const ok = warm.filter((r) => r.ok).length;
      console.log(`完成(成功 ${ok}/${warm.length})`);
      await sleep(cfg.betweenRoundsDelayMs);
    }

    const timed0 = Date.now();
    const allSamples = [];
    for (let round = 1; round <= cfg.messageTrials; round++) {
      process.stdout.write(`  第${round}/${cfg.messageTrials}轮 句数=${lv.sentences} ... `);
      const samples = await runConcurrencyLevel(
        cfg.baseUrl, token, accountId, 1, message, round, "msg"
      );
      for (const s of samples) {
        rawRows.push({
          message_level: lv.sentences,
          round,
          ms: s.ms,
          ok: s.ok,
          status: s.status,
          code: s.code,
          error: s.error || "",
        });
      }
      allSamples.push(...samples);
      const ok = samples.filter((s) => s.ok).length;
      console.log(`完成 成功=${ok}/${samples.length} 平均=${fmt(avgMs(samples))}`);
      if (round < cfg.messageTrials) await sleep(cfg.betweenRoundsDelayMs);
    }

    const wallMs = Date.now() - wall0;
    const timedWallMs =
      Date.now() - timed0 - (cfg.messageTrials - 1) * cfg.betweenRoundsDelayMs;
    const good = allSamples.filter((s) => s.ok);
    const summary = summarize(good, {
      message_level: lv.sentences,
      trials: cfg.messageTrials,
      errors: allSamples.length - good.length,
      wall_ms: wallMs,
    });
    summaryRows.push(summary);
    console.log(
      `  => 句数=${lv.sentences}: avg=${fmt(summary.avg_ms)} p50=${fmt(
        summary.p50_ms
      )} p95=${fmt(summary.p95_ms)} max=${fmt(summary.max_ms)} ` +
        `错误=${summary.errors} 吞吐≈${(summary.samples / (timedWallMs / 60000)).toFixed(1)} req/min`
    );
  }

  writeCsv(outFile, rawRows, [
    "message_level", "round", "ms", "ok", "status", "code", "error",
  ]);
  writeCsv(summaryFile, summaryRows, [
    "message_level", "trials", "samples", "avg_ms", "p50_ms", "p95_ms",
    "p99_ms", "min_ms", "max_ms", "errors", "wall_ms",
  ]);
  console.log(`已写入 ${path.relative(process.cwd(), outFile)} / ${path.relative(process.cwd(), summaryFile)}`);
}

// ---------------------------------------------------------------------------
// 统计 & CSV
// ---------------------------------------------------------------------------
function avgMs(samples) {
  if (!samples.length) return 0;
  return samples.reduce((a, b) => a + b.ms, 0) / samples.length;
}

function percentile(sorted, p) {
  if (!sorted.length) return 0;
  const idx = Math.min(
    sorted.length - 1,
    Math.max(0, Math.ceil(p * sorted.length) - 1)
  );
  return sorted[idx];
}

function summarize(samples, extra) {
  const sorted = samples.map((s) => s.ms).sort((a, b) => a - b);
  return {
    ...extra,
    samples: sorted.length,
    avg_ms: Math.round(sorted.reduce((a, b) => a + b, 0) / Math.max(1, sorted.length)),
    p50_ms: percentile(sorted, 0.5),
    p95_ms: percentile(sorted, 0.95),
    p99_ms: percentile(sorted, 0.99),
    min_ms: sorted[0] || 0,
    max_ms: sorted[sorted.length - 1] || 0,
  };
}

function writeCsv(file, rows, columns) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const esc = (v) => {
    if (v === null || v === undefined) return "";
    const s = String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const lines = [columns.join(",")];
  for (const row of rows) {
    lines.push(columns.map((c) => esc(row[c])).join(","));
  }
  fs.writeFileSync(file, "﻿" + lines.join("\r\n"), "utf8"); // BOM 便于 Excel 打开中文
}

function fmt(ms) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;
}

// ---------------------------------------------------------------------------
// 入口
// ---------------------------------------------------------------------------
async function main() {
  const args = parseArgs(process.argv.slice(2));
  const cmd = args._cmd || "both";

  const cfg = { ...CONFIG };
  const outDir = args["out-dir"]
    ? path.resolve(process.cwd(), args["out-dir"])
    : DEFAULT_OUT_DIR;
  if (args["base-url"]) cfg.baseUrl = args["base-url"];
  if (args.phone) cfg.phone = args.phone;
  if (args.password) cfg.password = args.password;
  if (args.users)
    cfg.concurrencyLevels = args.users.split(",").map((s) => Number(s));
  if (args.trials) {
    cfg.concurrencyTrials = Number(args.trials);
    cfg.complexityTrials = Number(args.trials);
  }
  if (args.levels)
    cfg.complexityLevels = args.levels.split(",").map((s) => Number(s));
  if (args["no-warmup"] !== undefined) cfg.warmup = false;

  if (!cfg.phone || !cfg.password) {
    console.error(
      "缺少登录账号。请用 --phone/--password 或环境变量 PERF_PHONE/PERF_PASSWORD。"
    );
    process.exit(1);
  }

  console.log(`目标服务器: ${cfg.baseUrl}`);
  console.log("登录获取 token ...");
  const { token, accountId } = await login(cfg.baseUrl, cfg.phone, cfg.password);
  console.log(`登录成功 accountId=${accountId}, token 前 12 位=${token.slice(0, 12)}...`);

  if (cmd === "concurrency" || cmd === "both") {
    console.log("\n===== 并发曲线 =====");
    await runConcurrency(
      cfg,
      token,
      accountId,
      path.join(outDir, "concurrency_raw.csv"),
      path.join(outDir, "concurrency_summary.csv")
    );
  }
  if (cmd === "complexity" || cmd === "both") {
    console.log("\n===== 复杂度曲线 =====");
    await runComplexity(
      cfg,
      token,
      path.join(outDir, "complexity_raw.csv"),
      path.join(outDir, "complexity_summary.csv")
    );
  }
  if (cmd === "message") {
    console.log("\n===== message 复杂度曲线（用户需求 1/3/6/10 句话）=====");
    cfg.messageLevels.forEach((lv) =>
      console.log(`  [${lv.sentences}句话] ${lv.message}`)
    );
    await runMessageComplexity(
      cfg,
      token,
      accountId,
      path.join(outDir, "message_raw.csv"),
      path.join(outDir, "message_summary.csv")
    );
  }

  console.log("\n完成。可用下面的命令绘图：");
  console.log(`  python scripts/plot_perf.py --dir "${outDir}"`);
}

main().catch((err) => {
  console.error(`\n出错: ${err.message}`);
  if (err.stack && process.env.DEBUG) console.error(err.stack);
  process.exit(1);
});
