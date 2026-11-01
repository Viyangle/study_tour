import json
import os

import run_experiment as exp

BASE = os.path.dirname(os.path.abspath(__file__))


def read_weight(name):
    result = {}
    for row in exp.read_tsv(name):
        if len(row) >= 3:
            result[(row[0], row[1])] = float(row[2])
    return result


def main():
    routes, stops, prior, candidates, accounts, dims = exp.load()
    after = read_weight("after.tsv")
    candidate_set = set(candidates)

    test_ids = []
    for rid, tag in routes.items():
        if tag not in dims:
            continue
        active_stops = [p for p in stops.get(rid, []) if p in candidate_set]
        if len(active_stops) >= 2 and rid % 5 == 0:
            test_ids.append(rid)
    test_ids.sort()

    before_metrics, used = exp.evaluate(routes, stops, candidates, test_ids, prior)
    after_metrics, _ = exp.evaluate(routes, stops, candidates, test_ids, after)

    changed = []
    by_tag = {}
    for key, old in prior.items():
        new = after.get(key, old)
        delta = new - old
        if abs(delta) > 1e-9:
            changed.append((key, old, new, delta))
            by_tag.setdefault(key[1], []).append(delta)
    max_abs = max((abs(x[3]) for x in changed), default=0.0)
    avg_abs = sum(abs(x[3]) for x in changed) / len(changed) if changed else 0.0
    top = sorted(changed, key=lambda x: -abs(x[3]))[:10]

    route_deltas = []
    for rid in test_ids:
        tag = routes[rid]
        relevant = {p for p in stops.get(rid, []) if p in candidate_set}
        if len(relevant) < 2:
            continue
        before_scores = {p: prior.get((p, tag), 0.0) for p in candidates}
        after_scores = {p: after.get((p, tag), 0.0) for p in candidates}
        _, before_ndcg, _ = exp.ranking_metrics(relevant, before_scores, 3)
        _, after_ndcg, _ = exp.ranking_metrics(relevant, after_scores, 3)
        route_deltas.append(after_ndcg - before_ndcg)
    win = sum(1 for d in route_deltas if d > 1e-9)
    loss = sum(1 for d in route_deltas if d < -1e-9)
    tie = len(route_deltas) - win - loss

    result = {
        "test_routes": before_metrics["n_routes"],
        "avg_relevant": round(before_metrics["avg_relevant"], 3),
        "win_loss_ndcg3": {"improved": win, "unchanged": tie, "worse": loss},
        "baseline": {k: round(v, 4) for k, v in before_metrics.items()
                     if k.startswith(("recall@", "ndcg@", "mrr@"))},
        "after_feedback": {k: round(v, 4) for k, v in after_metrics.items()
                           if k.startswith(("recall@", "ndcg@", "mrr@"))},
        "delta": {k: round(after_metrics[k] - before_metrics[k], 4)
                  for k in before_metrics if k.startswith(("recall@", "ndcg@", "mrr@"))},
        "weight_impact": {
            "rows_total": len(prior),
            "rows_changed": len(changed),
            "changed_ratio": round(len(changed) / len(prior), 4),
            "max_abs_delta": round(max_abs, 4),
            "avg_abs_delta_changed": round(avg_abs, 4),
            "top_changes": [
                {"poi": x[0][0], "tag": x[0][1], "before": round(x[1], 3),
                 "after": round(x[2], 3), "delta": round(x[3], 3)} for x in top
            ],
            "by_tag_avg_abs": {tag: round(sum(abs(d) for d in ds) / len(ds), 4)
                               for tag, ds in by_tag.items()},
        },
        "events": len([1 for _ in exp.read_tsv("events.sql")]) - 1,
    }
    with open(os.path.join(BASE, "result.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
