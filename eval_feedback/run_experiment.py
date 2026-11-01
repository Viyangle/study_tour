import csv
import json
import math
import os
import sys

BASE = os.path.dirname(os.path.abspath(__file__))


def read_tsv(name):
    path = os.path.join(BASE, name)
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            line = line.rstrip("\n\r")
            if not line:
                continue
            yield line.split("\t")


def load():
    routes = {}
    for row in read_tsv("routes.tsv"):
        if len(row) >= 2:
            routes[int(row[0])] = row[1]

    stops = {}
    for row in read_tsv("stops.tsv"):
        if len(row) >= 2:
            stops.setdefault(int(row[0]), []).append(row[1])

    prior = {}
    for row in read_tsv("prior.tsv"):
        if len(row) >= 3:
            prior[(row[0], row[1])] = float(row[2])

    candidates = []
    for row in read_tsv("candidates.tsv"):
        if row:
            candidates.append(row[0])

    accounts = []
    for row in read_tsv("accounts.tsv"):
        if row:
            accounts.append(int(row[0]))

    dims = []
    for row in read_tsv("dims.tsv"):
        if len(row) >= 2:
            dims.append(row[1])
    return routes, stops, prior, candidates, accounts, dims


def ranking_metrics(relevant, scores, k):
    ranked = sorted(scores, key=lambda p: (-scores[p], p))[:k]
    hits = sum(1 for p in ranked if p in relevant)
    denom = min(k, len(relevant))
    recall = hits / denom if denom else 0.0
    dcg = sum(1.0 / math.log2(i + 2) for i, p in enumerate(ranked) if p in relevant)
    idcg = sum(1.0 / math.log2(i + 2) for i in range(min(k, len(relevant))))
    ndcg = dcg / idcg if idcg else 0.0
    rr = 0.0
    for i, p in enumerate(ranked):
        if p in relevant:
            rr = 1.0 / (i + 1)
            break
    return recall, ndcg, rr


def evaluate(routes, stops, candidates, test_ids, score_map):
    ks = [3, 5, 10]
    totals = {k: [0.0, 0.0, 0.0] for k in ks}
    used = []
    for rid in test_ids:
        tag = routes[rid]
        relevant = {p for p in stops.get(rid, []) if p in candidates}
        if len(relevant) < 2:
            continue
        scores = {p: score_map.get((p, tag), 0.0) for p in candidates}
        for k in ks:
            r, n, rr = ranking_metrics(relevant, scores, k)
            totals[k][0] += r
            totals[k][1] += n
            totals[k][2] += rr
        used.append((rid, tag, len(relevant)))
    n = len(used)
    result = {"n_routes": n, "avg_relevant": sum(x[2] for x in used) / n if n else 0.0}
    for k in ks:
        result[f"recall@{k}"] = totals[k][0] / n if n else 0.0
        result[f"ndcg@{k}"] = totals[k][1] / n if n else 0.0
        result[f"mrr@{k}"] = totals[k][2] / n if n else 0.0
    return result, used


def main():
    routes, stops, prior, candidates, accounts, dims = load()
    candidate_set = set(candidates)
    test_ids = []
    train_ids = []
    for rid, tag in routes.items():
        if tag not in dims:
            continue
        active_stops = [p for p in stops.get(rid, []) if p in candidate_set]
        if len(active_stops) < 2:
            continue
        if rid % 5 == 0:
            test_ids.append(rid)
        else:
            train_ids.append(rid)
    test_ids.sort()
    train_ids.sort()

    baseline, used = evaluate(routes, stops, candidates, test_ids, prior)
    baseline["test_routes"] = len(test_ids)
    baseline["train_routes"] = len(train_ids)
    baseline["candidates"] = len(candidates)
    baseline["tags"] = len(dims)
    baseline["detail"] = used

    with open(os.path.join(BASE, "baseline.json"), "w", encoding="utf-8") as f:
        json.dump(baseline, f, ensure_ascii=False, indent=2)

    ordinal = {name: i for i, name in enumerate(dims)}
    sql = ["SET NAMES utf8mb4;"]
    events = 0
    for rid in train_ids:
        tag = routes[rid]
        tag_index = ordinal.get(tag)
        if tag_index is None:
            continue
        intent = [0.0] * len(dims)
        intent[tag_index] = 1.0
        intent_json = json.dumps(intent)
        account = accounts[rid % len(accounts)] if accounts else None
        for poi in stops.get(rid, []):
            if poi not in candidate_set:
                continue
            key = f"exp:{rid}:{poi}"
            sql.append(
                "INSERT IGNORE INTO tag_feedback_event"
                "(account_id,session_key,event_type,poi_id,event_value,intent_vector,"
                "occurred_at,idempotency_key,status,attempts) VALUES "
                f"({account},'exp','ROUTE_STOP_ADD','{poi}',0.600,'{intent_json}',NOW(),'{key}','PENDING',0);"
            )
            events += 1
    with open(os.path.join(BASE, "events.sql"), "w", encoding="utf-8") as f:
        f.write("\n".join(sql))

    print(json.dumps({
        "test_routes": len(test_ids),
        "train_routes": len(train_ids),
        "events": events,
        "baseline": {k: v for k, v in baseline.items() if k != "detail"}
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
