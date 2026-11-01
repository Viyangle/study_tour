import csv
import json
import os

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

BASE = os.path.dirname(os.path.abspath(__file__))
T = 2.776


def load():
    with open(os.path.join(BASE, "cv_summary.json"), encoding="utf-8") as f:
        summary = json.load(f)
    with open(os.path.join(BASE, "cv_folds.csv"), encoding="utf-8") as f:
        folds = list(csv.DictReader(f))
    return summary, folds


def style_header(ws, row=1):
    for cell in ws[row]:
        cell.font = Font(bold=True, color="FFFFFF")
        cell.fill = PatternFill("solid", fgColor="2F5597")
        cell.alignment = Alignment(horizontal="center", vertical="center")


def main():
    summary, folds = load()
    wb = Workbook()

    ws = wb.active
    ws.title = "Summary"
    ws["A1"] = "景点标签反馈闭环 5 折交叉验证"
    ws["A1"].font = Font(bold=True, size=16)
    ws["A2"] = "结论：工程链路跑通，但平均 NDCG/Recall 下降，95% CI 跨 0；当前不建议开启自动应用。"
    ws["A2"].font = Font(bold=True, color="C00000")
    ws.merge_cells("A2:H2")

    headers = ["指标", "反馈前均值", "反馈后均值", "Δ 均值", "Δ 标准差", "95% CI 下限", "95% CI 上限", "正向折数"]
    ws.append([])
    ws.append(headers)
    style_header(ws, 4)
    row = 5
    for key in ["recall@5", "ndcg@5", "mrr@5", "recall@10", "ndcg@10", "mrr@10"]:
        item = summary["summary"][key]
        half = T * item["delta_std"] / (5 ** 0.5)
        ws.cell(row=row, column=1, value=key)
        ws.cell(row=row, column=2, value=item["before_mean"])
        ws.cell(row=row, column=3, value=item["after_mean"])
        ws.cell(row=row, column=4, value=item["delta_mean"])
        ws.cell(row=row, column=5, value=item["delta_std"])
        ws.cell(row=row, column=6, value=item["delta_mean"] - half)
        ws.cell(row=row, column=7, value=item["delta_mean"] + half)
        ws.cell(row=row, column=8, value=f"{item['positive_folds']}/5")
        row += 1

    ws["A13"] = "设置：300 条历史路线；5 折；50 个 ACTIVE 景点；每会话最终站点 +0.8、2 个硬负例 -0.2；单用户单景点上限 0.8。"
    ws["A14"] = "注：这是离线重放，不是真实用户数据；负例按 prior Top 候选构造。"
    ws["A13"].alignment = Alignment(wrap_text=True)
    ws["A14"].alignment = Alignment(wrap_text=True)
    ws.merge_cells("A13:H13")
    ws.merge_cells("A14:H14")

    for i, width in enumerate([14, 14, 14, 13, 13, 14, 14, 12], start=1):
        ws.column_dimensions[get_column_letter(i)].width = width

    ws2 = wb.create_sheet("Folds")
    keys = ["fold", "train", "test", "sessions", "events",
            "before_recall@5", "after_recall@5", "delta_recall@5",
            "before_ndcg@5", "after_ndcg@5", "delta_ndcg@5",
            "before_ndcg@10", "after_ndcg@10", "delta_ndcg@10"]
    ws2.append(keys)
    style_header(ws2)
    for f in folds:
        ws2.append([float(f[k]) if k not in ("fold", "train", "test", "sessions", "events") else int(float(f[k]))
                    for k in keys])
    for i in range(1, len(keys) + 1):
        ws2.column_dimensions[get_column_letter(i)].width = 16

    ws3 = wb.create_sheet("RealAPIFlow")
    rows = [
        ["步骤", "结果"],
        ["第 1 次生成", "route 605"],
        ["第 2 次生成（同 memoryId）", "route 605 更新；中间事件 0"],
        ["发布", "POST /routes/605/publish -> project 3316"],
        ["ROUTE_STOP_CONFIRM", "4 条 +0.8 APPLIED"],
        ["ROUTE_STOP_REJECT", "3 条 -0.2 APPLIED"],
        ["PROJECT_PUBLISH", "1 条 IGNORED preference-only"],
        ["历史人文", "0.950 -> 0.960 (+0.010)"],
        ["科技探索", "0.450 -> 0.546 (+0.096)"],
        ["历史人文", "0.900 -> 0.920 (+0.020)"],
        ["博物馆研学", "0.950 -> 0.955 (+0.005)"],
        ["说明", "真实 API 流程验证；测试数据已清理"],
    ]
    for r in rows:
        ws3.append(r)
    style_header(ws3)
    ws3.column_dimensions["A"].width = 34
    ws3.column_dimensions["B"].width = 52

    graded_path = os.path.join(BASE, "graded_summary.json")
    if os.path.exists(graded_path):
        with open(graded_path, encoding="utf-8") as f:
            graded = json.load(f)
        ws4 = wb.create_sheet("GradedPairwise")
        ws4.append(["指标", "反馈前", "反馈后", "Δ", "Δ 标准差", "正向折数"])
        style_header(ws4)
        labels = {"pairwise": "Pairwise accuracy", "graded5": "Graded NDCG@5",
                  "graded10": "Graded NDCG@10", "pop": "Top10 平均常见度"}
        for key, label in labels.items():
            item = graded["summary"][key]
            ws4.append([label, item["before"], item["after"], item["delta"],
                        item["delta_std"], f"{item['positive_folds']}/5"])
        ws4.append([])
        ws4.append(["fold", "pairwise 前", "pairwise 后", "graded5 前", "graded5 后",
                    "graded10 前", "graded10 后", "pop 前", "pop 后"])
        for cell in ws4[ws4.max_row]:
            cell.font = Font(bold=True, color="FFFFFF")
            cell.fill = PatternFill("solid", fgColor="2F5597")
        for r in graded["folds"]:
            ws4.append([r["fold"], r["pairwise_before"], r["pairwise_after"],
                        r["graded5_before"], r["graded5_after"],
                        r["graded10_before"], r["graded10_after"],
                        r["pop_before"], r["pop_after"]])
        for i in range(1, 10):
            ws4.column_dimensions[get_column_letter(i)].width = 15

    out = os.path.join(BASE, "feedback_evaluation.xlsx")
    wb.save(out)
    print(out)


if __name__ == "__main__":
    main()
