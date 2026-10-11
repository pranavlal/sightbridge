"""Paired seed/group bootstrap and source-stratified comparison report."""
from collections import defaultdict
import json

import numpy as np

from acquire import digest, save
from detection_metrics import match, counts, summarize
from study_manifest import STUDY, CLASSES

ARMS = ["others", "barriers", "combined"]
SEEDS = [17, 29, 43]


def f1(count):
    tp, fp, fn = np.moveaxis(count, -1, 0)
    return 2 * tp / np.maximum(1, 2 * tp + fp + fn)


def analysis():
    records = [r for r in map(json.loads, (STUDY / "manifest.jsonl").read_text().splitlines()) if r["split"] == "test"]
    group_keys = []
    for r in records:
        # Entire collection/country/task/road units when known; conservative
        # perceptual clusters where no route/location metadata is provided.
        unit = r["evaluation_cluster"] if r["source"] in ("wet_potholes", "gutters") else r["group"]
        key = (r["source"], unit)
        if key not in group_keys:
            group_keys.append(key)
        r["bootstrap_group"] = group_keys.index(key)
    aggregate = np.zeros((3, 3, len(group_keys), 5, 3), dtype=float)
    metrics = {}
    initializations = {}
    for ai, arm in enumerate(ARMS):
        metrics[arm] = []
        for si, seed in enumerate(SEEDS):
            directory = STUDY / "runs" / f"{arm}-{seed}"
            result = json.loads((directory / "complete.json").read_text())
            validation = json.loads((directory / "validation.json").read_text())
            config = json.loads((directory / "config.json").read_text())
            if result["receipt"]["manifest_sha256"] != digest(STUDY / "manifest.jsonl"):
                raise ValueError("Result manifest changed")
            if result["receipt"]["protocol_sha256"] != digest(STUDY / "protocol.json"):
                raise ValueError("Protocol changed after training")
            initializations.setdefault(seed, []).append(config["initial_sha256"])
            predictions = json.loads((directory / "test_predictions.json").read_text())
            # Independent recalculation from saved raw detections.
            recalculated, _ = summarize(records, predictions, config["active"], validation["thresholds"])
            for label, values in recalculated.items():
                for metric in ("ap50", "ap75", "f1", "tp", "fp", "fn"):
                    old, new = result["test"][label][metric], values[metric]
                    if old is not None and not np.isclose(old, new):
                        raise ValueError("Saved evaluation disagrees with recomputed detections")
            for r in records:
                for label in config["active"]:
                    if label not in r["supervised"]:
                        continue
                    score, correct, ntruth = match(predictions[r["id"]], r["objects"], label)
                    value = counts(score, correct, ntruth, validation["thresholds"][str(label)])
                    aggregate[ai, si, r["bootstrap_group"], label - 1] += value
            metrics[arm].append(result)
    if any(len(set(v)) != 1 for v in initializations.values()):
        raise ValueError("Paired arms did not start from identical head initialization")

    indices_by_source = defaultdict(list)
    for gi, (source, _) in enumerate(group_keys):
        indices_by_source[source].append(gi)
    # Point estimates average seed-level F1, rather than pooling three repeated
    # evaluations of the same test images into invented additional samples.
    total = aggregate.sum(axis=2)
    scores = f1(total)
    primary_labels = [0, 1, 2]
    primary_delta = float((scores[2][:, primary_labels].mean(1) - scores[0][:, primary_labels].mean(1)).mean())
    rng = np.random.default_rng(20261012)
    seed_deltas, per_class_deltas, exterior_deltas = [], [], []
    other_groups = [i for i, (source, _) in enumerate(group_keys) if source != "barriers"]
    # Explicit indexing preserves seed/group dimensions (NumPy advanced indices
    # otherwise reorder those axes).
    exterior = aggregate[:, :, other_groups].sum(axis=2)
    exterior_scores = f1(exterior)
    exterior_point = float((exterior_scores[2][:, primary_labels].mean(1) - exterior_scores[0][:, primary_labels].mean(1)).mean())
    for _ in range(2000):
        weights = np.zeros(len(group_keys))
        for indices in indices_by_source.values():
            chosen = rng.choice(indices, size=len(indices), replace=True)
            weights += np.bincount(chosen, minlength=len(group_keys))
        seed_indices = rng.integers(0, 3, 3)
        weighted = (aggregate * weights[None, None, :, None, None]).sum(axis=2)
        values = f1(weighted)
        delta = values[2, seed_indices] - values[0, seed_indices]
        seed_deltas.append(float(delta[:, primary_labels].mean()))
        per_class_deltas.append(delta.mean(axis=0))
        exterior_weights = weights.copy()
        exterior_weights[[i for i, (source, _) in enumerate(group_keys) if source == "barriers"]] = 0
        weighted_exterior = (aggregate * exterior_weights[None, None, :, None, None]).sum(axis=2)
        values_exterior = f1(weighted_exterior)
        exterior_deltas.append(float((values_exterior[2, seed_indices][:, primary_labels] - values_exterior[0, seed_indices][:, primary_labels]).mean()))
    interval = np.percentile(seed_deltas, [2.5, 97.5]).tolist()
    per_class_intervals = np.percentile(np.asarray(per_class_deltas), [2.5, 97.5], axis=0)
    pooled = {}
    for arm in ARMS:
        pooled[arm] = {}
        for label, name in CLASSES.items():
            values = [r["test"].get(str(label)) for r in metrics[arm]]
            values = [v for v in values if v is not None]
            if not values:
                pooled[arm][name] = {"status": "not trained for this class"}
                continue
            pooled[arm][name] = {m: float(np.mean([v[m] for v in values])) for m in ("ap50", "ap75", "precision", "recall", "f1")}
            pooled[arm][name]["truth_objects"] = values[0]["truth_objects"]
            pooled[arm][name]["seed_f1"] = [v["f1"] for v in values]
    source_tables = {}
    for source in sorted(indices_by_source):
        source_tables[source] = {}
        for arm in ARMS:
            labels = sorted({label for result in metrics[arm] for label in result["sources"].get(source, {})})
            source_tables[source][arm] = {}
            for label in labels:
                values = [r["sources"][source][label] for r in metrics[arm]]
                source_tables[source][arm][CLASSES[int(label)]] = {m: float(np.mean([v[m] for v in values if v[m] is not None])) if any(v[m] is not None for v in values) else None
                    for m in ("ap50", "ap75", "precision", "recall", "f1")}
                source_tables[source][arm][CLASSES[int(label)]]["truth_objects"] = values[0]["truth_objects"]
    meaningful = primary_delta >= .05 and interval[0] > 0
    result = {"pooled_test": pooled, "source_test": source_tables,
        "primary": {"metric": "mean seed-level macro detection F1 over pothole, stairs, curb at IoU .5",
            "others": float(scores[0][:, primary_labels].mean()), "combined": float(scores[2][:, primary_labels].mean()),
            "difference": primary_delta, "paired_95_percent_ci": interval, "predeclared_minimum_gain": .05,
            "meets_meaningful_gain_rule": bool(meaningful)},
        "external_transfer": {"metric": "same macro F1, excluding accessibility images", "difference": exterior_point,
                              "paired_95_percent_ci": np.percentile(exterior_deltas, [2.5, 97.5]).tolist()},
        "class_f1_differences": {CLASSES[label + 1]: {"difference": float((scores[2, :, label] - scores[0, :, label]).mean()),
            "paired_95_percent_ci": per_class_intervals[:, label].tolist()} for label in primary_labels},
        "bootstrap": {"replicates": 2000, "training_seeds": SEEDS, "test_images": len(records),
            "units_per_source": {s: len(indices) for s, indices in indices_by_source.items()},
            "limitations": "Few independent collections; conditional source-stratified bootstrap is exploratory, not population-wide route confidence"},
        "checks": {"saved_metrics_recomputed": True, "paired_initialization_identical": True,
            "manifest_sha256": digest(STUDY / "manifest.jsonl"), "protocol_sha256": digest(STUDY / "protocol.json")},
        "deployment": "Not validated for walking, drop direction, or Android alerts"}
    save(STUDY / "comparison.json", result)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    analysis()
