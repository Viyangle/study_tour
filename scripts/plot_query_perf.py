# -*- coding: utf-8 -*-
"""
查询接口负载测试绘图脚本

读取 scripts/perf-query.js 生成的某次运行目录中的 query_summary.csv，画：
  ① 并发阶梯（mode=concurrency）：
     左图：并发数 -> 延迟（avg / p95）
     右图：并发数 -> 吞吐（req/s），并标注错误率
  ② 持续负载（mode=sustained）：
     各端点 bar 对比：avg / p95 / 吞吐

用法：
  python scripts/plot_query_perf.py                # 自动选择最新运行
  python scripts/plot_query_perf.py --dir perf/query-runs/<run-dir>
  python scripts/plot_query_perf.py --show         # 额外弹窗显示

依赖：matplotlib（可选；不装也可用 Excel 画）。CSV 读取只用标准库。
"""
import argparse
import csv
import os
import sys

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
except ImportError:
    print(
        "缺少 matplotlib。请先安装：\n"
        "  pip install matplotlib\n"
        "或者直接用 Excel 打开 perf/query_summary.csv 手工画图。",
        file=sys.stderr,
    )
    sys.exit(1)

SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_SOFT = "#52514e"
MUTED = "#898781"
GRID = "#e1e0d9"
AXIS = "#c3c2b7"
COLORS = ["#2a78d6", "#eb6834", "#2f9e6e", "#9a5bd3", "#d6a02a", "#4a7d8c"]

PERF_ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "perf")


def latest_run_dir(perf_root):
    runs_root = os.path.join(perf_root, "query-runs")
    if not os.path.isdir(runs_root):
        return None
    candidates = []
    for name in os.listdir(runs_root):
        run_dir = os.path.join(runs_root, name)
        summary = os.path.join(run_dir, "query_summary.csv")
        if os.path.isfile(summary):
            candidates.append((os.path.getmtime(summary), run_dir))
    return max(candidates, default=(None, None))[1]


def setup_fonts():
    candidates = [
        "Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "PingFang SC",
        "WenQuanYi Micro Hei", "Arial Unicode MS",
    ]
    plt.rcParams["font.sans-serif"] = candidates + ["DejaVu Sans"]
    plt.rcParams["axes.unicode_minus"] = False


def read_summary(path):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, "r", encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            rows.append(r)
    return rows


def style_axes(ax):
    ax.set_facecolor(SURFACE)
    for spine in ax.spines.values():
        spine.set_color(AXIS)
    ax.tick_params(colors=INK_SOFT, labelsize=10)
    ax.yaxis.grid(True, color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)


def series(rows, mode, endpoint):
    by_x = {}
    for r in rows:
        if r.get("mode") != mode:
            continue
        if endpoint is not None and r.get("endpoint") != endpoint:
            continue
        try:
            x = float(r["concurrency"])
        except (KeyError, ValueError, TypeError):
            continue
        by_x.setdefault(x, []).append(r)
    xs = sorted(by_x.keys())
    if not xs:
        return [], [], [], [], []
    avg, p95, thr, err = [], [], [], []
    for x in xs:
        group = by_x[x]
        avg.append(float(group[0]["avg_ms"]) / 1000.0)
        p95.append(float(group[0]["p95_ms"]) / 1000.0)
        thr.append(float(group[0]["throughput_req_s"]))
        err.append(float(group[0]["error_rate_pct"]))
    return xs, avg, p95, thr, err


def draw_concurrency(ax, title, ylabel, xs, ys, color, marker=""):
    style_axes(ax)
    ax.set_title(title, color=INK, fontsize=12, fontweight="bold", pad=8)
    ax.set_xlabel("并发用户数", color=INK_SOFT, fontsize=10)
    ax.set_ylabel(ylabel, color=INK_SOFT, fontsize=10)
    if xs:
        ax.plot(xs, ys, color=color, linewidth=2, marker=marker or "o",
                markersize=6, markerfacecolor=SURFACE, markeredgewidth=1.5,
                markeredgecolor=color, zorder=3)
        ax.set_xscale("log")
        ax.set_xticks(xs)
        ax.set_xticklabels([str(int(x)) for x in xs])


def main():
    ap = argparse.ArgumentParser(description="查询接口负载曲线")
    ap.add_argument("--show", action="store_true", help="弹窗显示图片")
    ap.add_argument("--dir", help="某次压测结果目录（默认自动选择 perf/query-runs 下最新一次）")
    args = ap.parse_args()

    setup_fonts()
    data_dir = os.path.abspath(args.dir) if args.dir else latest_run_dir(PERF_ROOT)
    if not data_dir:
        print("未找到压测运行目录，请先运行 node scripts/perf-query.js")
        return
    rows = read_summary(os.path.join(data_dir, "query_summary.csv"))
    if not rows:
        print(f"缺少 {os.path.join(data_dir, 'query_summary.csv')}，请先运行压测脚本")
        return

    # mix 场景还会写入各子端点诊断行；主曲线只取 overall，避免重复统计。
    cc_rows = [r for r in rows
               if r.get("mode") == "concurrency" and r.get("scope", "overall") == "overall"]
    st_rows = [r for r in rows
               if r.get("mode") == "sustained" and r.get("scope", "overall") == "overall"]
    endpoints = sorted({r.get("endpoint", "") for r in rows if r.get("endpoint")})

    fig, axes = plt.subplots(1, 2, figsize=(13, 5.2), dpi=150)
    fig.patch.set_facecolor(SURFACE)
    fig.suptitle("查询接口负载测试（延迟 / 吞吐）", color=INK, fontsize=16,
                 fontweight="bold", y=0.98)

    drawn = False
    for idx, ep in enumerate(endpoints):
        color = COLORS[idx % len(COLORS)]
        xs, avg, p95, thr, err = series(cc_rows, "concurrency", ep)
        if not xs:
            continue
        drawn = True
        axes[0].plot(xs, avg, color=color, linewidth=2, marker="o",
                     markersize=6, markerfacecolor=SURFACE, markeredgewidth=1.5,
                     markeredgecolor=color, zorder=3, label=f"{ep} avg")
        axes[0].plot(xs, p95, color=color, linewidth=1.4, linestyle="--",
                     alpha=0.75, marker="x", markersize=5, zorder=2,
                     label=f"{ep} p95")
        axes[1].plot(xs, thr, color=color, linewidth=2, marker="s",
                     markersize=6, markerfacecolor=SURFACE, markeredgewidth=1.5,
                     markeredgecolor=color, zorder=3, label=ep)

    for ax in axes:
        style_axes(ax)
        ax.set_xlabel("并发用户数", color=INK_SOFT, fontsize=10)
        if drawn:
            ax.set_xscale("log")
            all_x = sorted({x for ep in endpoints
                            for x in series(cc_rows, "concurrency", ep)[0]})
            ax.set_xticks(all_x)
            ax.set_xticklabels([str(int(x)) for x in all_x])
    axes[0].set_ylabel("延迟 (秒)", color=INK_SOFT, fontsize=10)
    axes[0].set_title("并发阶梯 · 延迟（avg / p95）", color=INK, fontsize=12,
                      fontweight="bold", pad=8)
    axes[1].set_ylabel("吞吐 (req/s)", color=INK_SOFT, fontsize=10)
    axes[1].set_title("并发阶梯 · 吞吐", color=INK, fontsize=12,
                      fontweight="bold", pad=8)
    if drawn:
        axes[0].legend(frameon=False, fontsize=8, ncols=2)
        axes[1].legend(frameon=False, fontsize=8)

    if not cc_rows:
        axes[0].text(0.5, 0.5, "缺少 concurrency 模式数据\n请运行 node scripts/perf-query.js concurrency",
                     ha="center", va="center", color=MUTED, transform=axes[0].transAxes)
        axes[1].text(0.5, 0.5, "缺少 concurrency 模式数据",
                     ha="center", va="center", color=MUTED, transform=axes[1].transAxes)

    fig.tight_layout(rect=[0, 0, 1, 0.95])
    out1 = os.path.join(data_dir, "query_perf.png")
    fig.savefig(out1, facecolor=SURFACE, bbox_inches="tight")
    print(f"已保存: {os.path.abspath(out1)}")

    if st_rows:
        fig2, ax = plt.subplots(figsize=(10, 5), dpi=150)
        fig2.patch.set_facecolor(SURFACE)
        style_axes(ax)
        labels = []
        avg_v, p95_v = [], []
        for r in st_rows:
            labels.append(r.get("endpoint", "?"))
            avg_v.append(float(r["avg_ms"]) / 1000.0)
            p95_v.append(float(r["p95_ms"]) / 1000.0)
        x = list(range(len(labels)))
        w = 0.35
        ax.bar([i - w / 2 for i in x], avg_v, w, label="平均延迟", color=COLORS[0])
        ax.bar([i + w / 2 for i in x], p95_v, w, label="P95 延迟", color=COLORS[1])
        ax.set_xticks(x)
        ax.set_xticklabels(labels)
        ax.set_ylabel("延迟 (秒)", color=INK_SOFT, fontsize=10)
        ax.set_title("持续负载 · 稳态延迟（固定并发跑满时长）",
                     color=INK, fontsize=13, fontweight="bold", pad=10)
        for i, v in enumerate(avg_v):
            ax.text(i - w / 2, v, f"{v:.2f}s", ha="center", va="bottom", fontsize=8, color=INK_SOFT)
        for i, v in enumerate(p95_v):
            ax.text(i + w / 2, v, f"{v:.2f}s", ha="center", va="bottom", fontsize=8, color=INK_SOFT)
        ax.legend(frameon=False)
        fig2.tight_layout()
        out2 = os.path.join(data_dir, "query_sustained_perf.png")
        fig2.savefig(out2, facecolor=SURFACE, bbox_inches="tight")
        print(f"已保存: {os.path.abspath(out2)}")

    if args.show:
        plt.show()


if __name__ == "__main__":
    main()
