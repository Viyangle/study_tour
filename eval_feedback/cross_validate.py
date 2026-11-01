import csv
import json
import math
import os
import statistics
from collections import Counter

import run_experiment as exp

BASE = os.path.dirname(os.path.abspath(__file__))
CAP = 0.8
KAPPA = 3.0


def effective(prior, pos, neg):
    return max(0.0, min(1.0, (KAPPA * prior + pos) / (KAPPA + pos + neg)))


def load_all():
    routes, stops, prior, candidates, accounts, dims = exp.load()
    tag_index = {name: i for i, name in enumerate(dims)}
    by_poi = {}
    for (poi, tag), w in prior.items():
        by_poi.setdefault(poi, {})[tag] = w
    return routes, stops, prior, candidates, accounts, dims, tag_index, by_poi


def apply_event(state, poi, tag, value, account, used):
    """和 TagFeedbackProcessor 的单事件 credit 分配一致。"""
    key = (account, poi)
    remaining = CAP - used.get(key, 0.0)
    if remaining <= 1e-9:
        return 0
    applied_value = min(abs(value), remaining)
    used[key] = used.get(key, 0.0) + applied_value

    vector = {tag_name: state[(poi, tag_name)][0] for tag_name in state.get(poi, {})}
    if not vector:
        return 0
    total_vector = sum(vector.values())
    if total_vector <= 0:
        return 0
    intent = 1.0 if tag in vector else 0.0
    # credit 只在有向量的维度上分配
    credits = {}
    total_credit = 0.0
    for tag_name, weight in vector.items():
        c = (0.05 + (1.0 if tag_name == tag else 0.0)) * weight
        credits[tag_name] = c
        total_credit += c
    if total_credit <= 1e-9:
        return 0
    for tag_name, c in credits.items():
        delta = applied_value * (c / total_credit)
        prior, pos, neg = state[(poi, tag_name)]
        if value > 0:
            pos += delta
        else:
            neg += delta
        state[(poi, tag_name)] = (prior, pos, neg)
    return 1


def build_state(by_poi):
    state = {}
    for poi, tags in by_poi.items():
        state[poi] = {}
        for tag, prior in tags.items():
            state[poi][tag] = (prior, 0.0, 0.0)
            state[(poi, tag)] = (prior, 0.0, 0.0)
    return state


def score_map(state, candidates, dims):
    result = {}
    for poi in candidates:
        for tag in dims:
            cell = state.get((poi, tag))
            result[(poi, tag)] = effective(*cell) if cell else 0.0
    return result


def hard_negatives(routes, stops, prior, candidates, rid, tag, limit=2):
    final = {p for p in stops.get(rid, []) if p in candidates}
    ranked = sorted(candidates, key=lambda p: (-prior.get((p, tag), 0.0), p))
    result = []
    for p in ranked:
        if p not in final:
            result.append(p)
        if len(result) >= limit:
            break
    return result


def fold_run(routes, stops, prior, candidates, accounts, dims, tag_index, by_poi, fold):
    train = [rid for rid, tag in routes.items()
             if tag in tag_index and rid % 5 != fold
             and len([p for p in stops.get(rid, []) if p in candidates]) >= 2]
    test = [rid for rid, tag in routes.items()
            if tag in tag_index and rid % 5 == fold
            and len([p for p in stops.get(rid, []) if p in candidates]) >= 2]
    state = build_state(by_poi)
    used = {}
    sessions = 0
    events = 0
    for rid in train:
        tag = routes[rid]
        final = sorted({p for p in stops.get(rid, []) if p in candidates})
        if not final:
            continue
        account = accounts[rid % len(accounts)]
        negatives = hard_negatives(routes, stops, prior, candidates, rid, tag, 2)
        for poi in final:
            events += apply_event(state, poi, tag, 0.8, account, used)
        for poi in negatives:
            events += apply_event(state, poi, tag, -0.2, account, used)
        sessions += 1
    scores = score_map(state, candidates, dims)
    return train, test, scores, sessions, events


def metrics(routes, stops, candidates, dims, test_ids, score_fn):
    ks = [5, 10]
    totals = {k: [0.0, 0.0, 0.0] for k in ks}
    rows = []
    for rid in test_ids:
        tag = routes[rid]
        relevant = {p for p in stops.get(rid, []) if p in candidates}
        if len(relevant) < 2:
            continue
        scores = {p: score_fn(p, tag) for p in candidates}
        ranked = sorted(candidates, key=lambda p: (-scores[p], p))
        row = {}
        for k in ks:
            top = ranked[:k]
            hits = sum(1 for p in top if p in relevant)
            recall = hits / min(k, len(relevant))
            dcg = sum(1.0 / math.log2(i + 2) for i, p in enumerate(top) if p in relevant)
            idcg = sum(1.0 / math.log2(i + 2) for i in range(min(k, len(relevant))))
            ndcg = dcg / idcg if idcg else 0.0
            rr = 0.0
            for i, p in enumerate(ranked):
                if p in relevant:
                    rr = 1.0 / (i + 1)
                    break
            totals[k][0] += recall
            totals[k][1] += ndcg
            totals[k][2] += rr
            row[f"recall@{k}"] = recall
            row[f"ndcg@{k}"] = ndcg
            row[f"mrr@{k}"] = rr
        rows.append((rid, tag, row))
    n = len(rows)
    result = {f"{name}@{k}": totals[k][i] / n
              for k in ks for i, name in enumerate(("recall", "ndcg", "mrr"))}
    result["n"] = n
    return result, rows


def main():
    routes, stops, prior, candidates, accounts, dims, tag_index, by_poi = load_all()
    folds = []
    for fold in range(5):
        train, test, scores, sessions, events = fold_run(
            routes, stops, prior, candidates, accounts, dims, tag_index, by_poi, fold)
        before, _ = metrics(routes, stops, candidates, dims, test,
                            lambda p, t: prior.get((p, t), 0.0))
        after, _ = metrics(routes, stops, candidates, dims, test,
                           lambda p, t: scores.get((p, t), 0.0))
        row = {"fold": fold, "train": len(train), "test": before["n"],
               "sessions": sessions, "events": events}
        for k in (5, 10):
            for name in ("recall", "ndcg", "mrr"):
                key = f"{name}@{k}"
                row[f"before_{key}"] = before[key]
                row[f"after_{key}"] = after[key]
                row[f"delta_{key}"] = after[key] - before[key]
        folds.append(row)
        print(f"fold={fold} train={len(train)} test={before['n']} sessions={sessions} events={events}")
        for k in (5, 10):
            print(f"  k={k} recall {before[f'recall@{k}']:.4f}->{after[f'recall@{k}']:.4f} "
                  f"({after[f'recall@{k}']-before[f'recall@{k}']:+.4f}) "
                  f"ndcg {before[f'ndcg@{k}']:.4f}->{after[f'ndcg@{k}']:.4f} "
                  f"({after[f'ndcg@{k}']-before[f'ndcg@{k}']:+.4f})")

    summary = {}
    for k in (5, 10):
        for name in ("recall", "ndcg", "mrr"):
            key = f"{name}@{k}"
            deltas = [f[f"delta_{key}"] for f in folds]
            before = [f[f"before_{key}"] for f in folds]
            after = [f[f"after_{key}"] for f in folds]
            summary[key] = {
                "before_mean": statistics.mean(before),
                "after_mean": statistics.mean(after),
                "delta_mean": statistics.mean(deltas),
                "delta_std": statistics.stdev(deltas) if len(deltas) > 1 else 0.0,
                "positive_folds": sum(1 for d in deltas if d > 0),
                "negative_folds": sum(1 for d in deltas if d < 0),
            }

    with open(os.path.join(BASE, "cv_folds.csv"), "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(folds[0].keys()))
        writer.writeheader()
        writer.writerows(folds)
    with open(os.path.join(BASE, "cv_summary.json"), "w", encoding="utf-8") as fh:
        json.dump({"folds": folds, "summary": summary}, fh, ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
