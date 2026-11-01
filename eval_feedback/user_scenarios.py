import os
from collections import Counter

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

    test_ids = sorted(rid for rid, tag in routes.items()
                      if tag in dims and rid % 5 == 0
                      and len([p for p in stops.get(rid, []) if p in candidate_set]) >= 2)
    train_ids = [rid for rid in routes if rid % 5 != 0]

    freq = Counter()
    for rid in train_ids:
        for p in set(stops.get(rid, [])):
            if p in candidate_set:
                freq[p] += 1
    ranked_freq = freq.most_common()
    common = {p for p, n in ranked_freq[:10]}
    rare = candidate_set - common
    print("common", len(common), "rare", len(rare))
    print("top common", ranked_freq[:10])

    ks = [3, 5, 10]
    out = {}
    for label, weights in (("prior", prior), ("after", after)):
        common_hits = {k: 0 for k in ks}
        rare_hits = {k: 0 for k in ks}
        common_total = 0
        rare_total = 0
        pop_sum = {k: 0.0 for k in ks}
        top_sets = {k: set() for k in ks}
        for rid in test_ids:
            tag = routes[rid]
            relevant = {p for p in stops.get(rid, []) if p in candidate_set}
            relevant_common = relevant & common
            relevant_rare = relevant & rare
            common_total += len(relevant_common)
            rare_total += len(relevant_rare)
            ranked = sorted(candidate_set, key=lambda p: (-weights.get((p, tag), 0.0), p))
            for k in ks:
                top = ranked[:k]
                common_hits[k] += len(set(top) & relevant_common)
                rare_hits[k] += len(set(top) & relevant_rare)
                top_sets[k].update(top)
                pop_sum[k] += sum(freq.get(p, 0) for p in top)
        n = len(test_ids)
        out[label] = {
            "common_recall": {k: round(common_hits[k] / n, 4) for k in ks},
            "rare_recall": {k: round(rare_hits[k] / n, 4) for k in ks},
            "top_popularity": {k: round(pop_sum[k] / n, 2) for k in ks},
            "coverage": {k: len(top_sets[k]) for k in ks},
        }

    # 用户手动选择：每条测试路线，用户把目标站点加进路线；看该站点在候选排序中的名次变化
    prior_ranks = []
    after_ranks = []
    for rid in test_ids:
        tag = routes[rid]
        for p in stops.get(rid, []):
            if p not in candidate_set:
                continue
            before = sorted(candidate_set, key=lambda x: (-prior.get((x, tag), 0.0), x)).index(p) + 1
            after_rank = sorted(candidate_set, key=lambda x: (-after.get((x, tag), 0.0), x)).index(p) + 1
            prior_ranks.append(before)
            after_ranks.append(after_rank)

    out["manual_pick_rank"] = {
        "samples": len(prior_ranks),
        "avg_before": round(sum(prior_ranks) / len(prior_ranks), 2),
        "avg_after": round(sum(after_ranks) / len(after_ranks), 2),
        "top10_before": sum(1 for x in prior_ranks if x <= 10),
        "top10_after": sum(1 for x in after_ranks if x <= 10),
        "top20_before": sum(1 for x in prior_ranks if x <= 20),
        "top20_after": sum(1 for x in after_ranks if x <= 20),
    }
    print(out)


if __name__ == "__main__":
    main()
