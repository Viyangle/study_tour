# -*- coding: utf-8 -*-
"""
AI 路线生成性能曲线绘图脚本

读取 scripts/perf-ai-route.js 生成的 perf/*_summary.csv（及 *_raw.csv），
画出两条曲线：
  左图：并发用户数 -> 平均生成时间
  右图：提交景点数 N（路线复杂程度） -> 生成时间

用法：
  python scripts/plot_perf.py                # 保存 perf/ai_route_perf.png
  python scripts/plot_perf.py --show         # 额外弹窗显示

依赖：matplotlib、pandas（或仅 csv 标准库）。没有 pandas 也能跑（用 csv 模块）。
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
        "或者直接用 Excel 打开 perf/*_summary.csv 手工画图。",
        file=sys.stderr,
    )
    sys.exit(1)

# ---------------------------------------------------------------------------
# 调色板（dataviz 参考调色板：浅色面）
# ---------------------------------------------------------------------------
SURFACE   = "#fcfcfb"
INK       = "#0b0b0b"
INK_SOFT  = "#52514e"
MUTED     = "#898781"
GRID      = "#e1e0d9"
AXIS      = "#c3c2b7"
BLUE      = "#2a78d6"   # 并发曲线（系列 1）
ORANGE    = "#eb6834"   # 复杂度曲线（系列 2）

PERF_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "perf")


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


def read_raw(path):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, "r", encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            if r.get("ok", "").lower() == "true":
                rows.append(r)
    return rows


def percentile(values, p):
    s = sorted(values)
    if not s:
        return 0
    idx = min(len(s) - 1, max(0, int(p * len(s)) - 1))
    return s[idx]


def series_data(x_key, summary_path, raw_path):
    """返回 (xs, avg_s, band_lo_s, band_hi_s)，时间为秒。优先用 raw 算 p5/p95 带。"""
    summary = read_summary(summary_path)
    raw = read_raw(raw_path)

    by_x = {}
    for s in summary:
        try:
            x = float(s[x_key])
        except (ValueError, TypeError):
            continue
        by_x.setdefault(x, {"avg_ms": None})
        by_x[x]["avg_ms"] = float(s["avg_ms"])

    xs = sorted(by_x.keys())
    if not xs:
        return [], [], [], []

    if raw:
        raw_by_x = {}
        for r in raw:
            try:
                x = float(r[x_key])
                ms = float(r["ms"])
            except (ValueError, TypeError):
                continue
            raw_by_x.setdefault(x, []).append(ms)

    avg_s, lo_s, hi_s = [], [], []
    for x in xs:
        avg_ms = by_x[x]["avg_ms"]
        avg_s.append(avg_ms / 1000.0 if avg_ms is not None else 0.0)
        samples = raw_by_x.get(x, [])
        if len(samples) >= 2:
            lo_s.append(min(samples) / 1000.0)          # 下界：最小值
            hi_s.append(percentile(samples, 0.95) / 1000.0)  # 上界：p95
        else:
            lo_s.append(avg_s[-1])
            hi_s.append(avg_s[-1])
    return xs, avg_s, lo_s, hi_s


def style_axes(ax):
    ax.set_facecolor(SURFACE)
    for spine in ax.spines.values():
        spine.set_color(AXIS)
    ax.tick_params(colors=INK_SOFT, labelsize=10)
    ax.yaxis.grid(True, color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    ax.ticklabel_format(useOffset=False)


def draw_panel(ax, title, xlabel, xs, avg_s, lo_s, hi_s, color):
    style_axes(ax)
    ax.set_title(title, color=INK, fontsize=13, fontweight="bold", pad=10)
    ax.set_xlabel(xlabel, color=INK_SOFT, fontsize=11)
    ax.set_ylabel("平均生成时间 (秒)", color=INK_SOFT, fontsize=11)

    # p5–p95 带宽带（浅色，先画）
    ax.fill_between(xs, lo_s, hi_s, color=color, alpha=0.15, linewidth=0, zorder=1)
    # 平均线 + 标记（2px 线，>=8px 标记）
    ax.plot(xs, avg_s, color=color, linewidth=2, marker="o",
            markersize=8, markerfacecolor=SURFACE, markeredgewidth=2,
            markeredgecolor=color, zorder=3)
    # 选择性直接标注最后一个点的数值
    if avg_s:
        last_x, last_v = xs[-1], avg_s[-1]
        ax.annotate(f"{last_v:.1f}s", xy=(last_x, last_v),
                    xytext=(0, 9), textcoords="offset points",
                    ha="center", color=INK, fontsize=10)
        ax.set_xlim(xs[0] - 0.5, xs[-1] + 1.0)


def main():
    ap = argparse.ArgumentParser(description="AI 路线性能曲线")
    ap.add_argument("--show", action="store_true", help="弹窗显示图片")
    ap.add_argument("--dir", default=PERF_DIR, help="perf 数据目录（默认 ../perf）")
    args = ap.parse_args()

    setup_fonts()

    data_dir = os.path.abspath(args.dir)

    cc_x, cc_avg, cc_lo, cc_hi = series_data(
        "concurrency",
        os.path.join(data_dir, "concurrency_summary.csv"),
        os.path.join(data_dir, "concurrency_raw.csv"),
    )
    cx_x, cx_avg, cx_lo, cx_hi = series_data(
        "n",
        os.path.join(data_dir, "complexity_summary.csv"),
        os.path.join(data_dir, "complexity_raw.csv"),
    )

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13, 5.2), dpi=150)
    fig.patch.set_facecolor(SURFACE)
    fig.suptitle("AI 路线生成性能", color=INK, fontsize=16, fontweight="bold", y=0.98)

    if cc_x:
        draw_panel(ax1, "多用户并发 · 平均生成时间", "并发用户数",
                   cc_x, cc_avg, cc_lo, cc_hi, BLUE)
    else:
        ax1.text(0.5, 0.5, "缺少 perf/concurrency_summary.csv\n请先运行 node scripts/perf-ai-route.js",
                 ha="center", va="center", color=MUTED, transform=ax1.transAxes)
        ax1.set_axis_off()

    if cx_x:
        draw_panel(ax2, "路线复杂程度（景点数 N）· 生成时间", "提交景点数 N",
                   cx_x, cx_avg, cx_lo, cx_hi, ORANGE)
    else:
        ax2.text(0.5, 0.5, "缺少 perf/complexity_summary.csv\n请先运行 node scripts/perf-ai-route.js",
                 ha="center", va="center", color=MUTED, transform=ax2.transAxes)
        ax2.set_axis_off()

    fig.tight_layout(rect=[0, 0, 1, 0.95])
    out = os.path.join(data_dir, "ai_route_perf.png")
    fig.savefig(out, facecolor=SURFACE, bbox_inches="tight")
    print(f"已保存: {os.path.abspath(out)}")
    if args.show:
        plt.show()


if __name__ == "__main__":
    main()
