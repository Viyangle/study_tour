import csv
import json
import math
import os
import statistics
from collections import Counter

import cross_validate as cv
import run_experiment as exp

BASE = os.path.dirname(os.path.abspath(__file__))


def graded_ndcg(relevant, scores, candidates, common, k):
    def gain(p):
        if p in relevant:
            return 1.0
        if p in common:
            return 0.3
        return 0.0

    ranked = sorted(candidates, key=lambda p: (-scores[p], p))[:k]
    dcg = sum(gain(p) / math.log2(i + 2) for i, p in enumerate(ranked))
    ideal = sorted((gain(p) for p in candidates), reverse=True)[:k]
    idcg = sum(g / math.log2(i + 2) for i, g in enumerate(ideal))
    return dcg / idcg if idcg else 0.0


def pairwise_accuracy(final, negatives, scores):
    wins = 0.0
    pairs = 0
    for p in final:
        for n in negatives:
            pairs += 1
            if scores[p] > scores[n]:
                wins += 1
            elif scores[p] == scores[n]:
                wins += 0.5
    return wins / pairs if pairs else 0.0


def main():
    routes, stops, prior, candidates, accounts, dims, tag_index, by_poi = cv.load_all()
    results = []
    for fold in range(5):
        train, test, after_scores, sessions, events = cv.fold_run(
            routes, stops, prior, candidates, accounts, dims, tag_index, by_poi, fold)
        freq = Counter()
        for rid in train:
            for p in set(stops.get(rid, [])):
                if p in set(candidates):
                    freq[p] += 1
        common = {p for p, _ in freq.most_common(10)}

        before_pairs = []
        after_pairs = []
        before_g5 = []
        after_g5 = []
        before_g10 = []
        after_g10 = []
        before_pop = []
        after_pop = []
        for rid in test:
            tag = routes[rid]
            final = {p for p in stops.get(rid, []) if p in candidates}
            if len(final) < 2:
                continue
            negatives = cv.hard_negatives(routes, stops, prior, candidates, rid, tag, 2)
            if not negatives:
                continue
            b_scores = {p: prior.get((p, tag), 0.0) for p in candidates}
            a_scores = {p: after_scores.get((p, tag), 0.0) for p in candidates}
            before_pairs.append(pairwise_accuracy(final, negatives, b_scores))
            after_pairs.append(pairwise_accuracy(final, negatives, a_scores))
            before_g5.append(graded_ndcg(final, b_scores, candidates, common, 5))
            after_g5.append(graded_ndcg(final, a_scores, candidates, common, 5))
            before_g10.append(graded_ndcg(final, b_scores, candidates, common, 10))
            after_g10.append(graded_ndcg(final, a_scores, candidates, common, 10))
            b_ranked = sorted(candidates, key=lambda p: (-b_scores[p], p))[:10]
            a_ranked = sorted(candidates, key=lambda p: (-a_scores[p], p))[:10]
            before_pop.append(sum(freq.get(p, 0) for p in b_ranked) / 10)
            after_pop.append(sum(freq.get(p, 0) for p in a_ranked) / 10)

        row = {
            "fold": fold,
            "test": len(before_pairs),
            "pairwise_before": sum(before_pairs) / len(before_pairs),
            "pairwise_after": sum(after_pairs) / len(after_pairs),
            "graded5_before": sum(before_g5) / len(before_g5),
            "graded5_after": sum(after_g5) / len(after_g5),
            "graded10_before": sum(before_g10) / len(before_g10),
            "graded10_after": sum(after_g10) / len(after_g10),
            "pop_before": sum(before_pop) / len(before_pop),
            "pop_after": sum(after_pop) / len(after_pop),
            "sessions": sessions,
            "events": events,
        }
        results.append(row)
        print(f"fold={fold} test={row['test']} "
              f"pair {row['pairwise_before']:.3f}->{row['pairwise_after']:.3f} "
              f"gNDCG5 {row['graded5_before']:.3f}->{row['graded5_after']:.3f} "
              f"gNDCG10 {row['graded10_before']:.3f}->{row['graded10_after']:.3f} "
              f"pop10 {row['pop_before']:.1f}->{row['pop_after']:.1f}")

    print("\nMEAN")
    summary = {}
    for key in ("pairwise", "graded5", "graded10", "pop"):
        before = sum(r[f"{key}_before"] for r in results) / len(results)
        after = sum(r[f"{key}_after"] for r in results) / len(results)
        deltas = [r[f"{key}_after"] - r[f"{key}_before"] for r in results]
        summary[key] = {
            "before": before,
            "after": after,
            "delta": after - before,
            "delta_std": statistics.stdev(deltas),
            "positive_folds": sum(1 for d in deltas if d > 0),
        }
        print(f"{key}: {before:.4f} -> {after:.4f} ({after-before:+.4f})")

    with open(os.path.join(BASE, "graded_folds.csv"), "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(results[0].keys()))
        writer.writeheader()
        writer.writerows(results)
    with open(os.path.join(BASE, "graded_summary.json"), "w", encoding="utf-8") as fh:
        json.dump({"folds": results, "summary": summary}, fh, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
