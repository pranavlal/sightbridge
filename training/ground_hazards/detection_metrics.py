"""Detection metrics with one-to-one matching and explicit missing labels."""
import numpy as np


def iou(box, boxes):
    boxes = np.asarray(boxes, dtype=float).reshape(-1, 4)
    if not len(boxes):
        return np.zeros(0)
    intersection = np.maximum(0, np.minimum(box[2:], boxes[:, 2:]) - np.maximum(box[:2], boxes[:, :2])).prod(1)
    union = np.maximum(0, box[2] - box[0]) * np.maximum(0, box[3] - box[1]) + (boxes[:, 2:] - boxes[:, :2]).prod(1) - intersection
    return intersection / np.maximum(union, 1e-12)


def match(prediction, objects, label, overlap=.5):
    selected = [(s, b) for s, b, c in zip(prediction["scores"], prediction["boxes"], prediction["labels"]) if c == label]
    selected.sort(key=lambda x: -x[0])
    truth = np.asarray([o["box"] for o in objects if o["label"] == label], dtype=float).reshape(-1, 4)
    used = set()
    scores, correct = [], []
    for score, box in selected:
        overlaps = iou(np.asarray(box), truth)
        candidates = [i for i in range(len(truth)) if i not in used and overlaps[i] >= overlap]
        found = max(candidates, key=lambda i: overlaps[i]) if candidates else None
        if found is not None:
            used.add(found)
        scores.append(score)
        correct.append(found is not None)
    return np.asarray(scores), np.asarray(correct, dtype=bool), len(truth)


def average_precision(scores, correct, ntruth):
    if ntruth == 0:
        return None
    if not len(scores):
        return 0.
    order = np.argsort(-scores, kind="stable")
    tp = np.cumsum(correct[order])
    fp = np.cumsum(~correct[order])
    recall = tp / ntruth
    precision = tp / np.maximum(1, tp + fp)
    envelope = np.maximum.accumulate(precision[::-1])[::-1]
    values = []
    for r in np.linspace(0, 1, 101):
        positions = np.where(recall >= r)[0]
        values.append(float(envelope[positions[0]]) if len(positions) else 0.)
    return float(np.mean(values))


def counts(scores, correct, ntruth, threshold):
    keep = scores >= threshold
    tp = int(np.count_nonzero(correct & keep))
    fp = int(np.count_nonzero(~correct & keep))
    return np.array([tp, fp, ntruth - tp], dtype=float)


def rates(count):
    tp, fp, fn = np.asarray(count)
    return {"precision": float(tp / max(1, tp + fp)), "recall": float(tp / max(1, tp + fn)),
            "f1": float(2 * tp / max(1, 2 * tp + fp + fn)), "tp": int(tp), "fp": int(fp), "fn": int(fn)}


def summarize(records, predictions, labels, thresholds=None):
    output, chosen = {}, {}
    for label in labels:
        values = [match(predictions[r["id"]], r["objects"], label) for r in records if label in r["supervised"]]
        scores = np.concatenate([v[0] for v in values]) if values else np.zeros(0)
        correct = np.concatenate([v[1] for v in values]) if values else np.zeros(0, dtype=bool)
        ntruth = sum(v[2] for v in values)
        ap50 = average_precision(scores, correct, ntruth)
        values75 = [match(predictions[r["id"]], r["objects"], label, .75) for r in records if label in r["supervised"]]
        scores75 = np.concatenate([v[0] for v in values75]) if values75 else np.zeros(0)
        correct75 = np.concatenate([v[1] for v in values75]) if values75 else np.zeros(0, dtype=bool)
        if thresholds is None:
            candidates = np.linspace(.05, .95, 37)
            threshold = max(candidates, key=lambda t: (rates(counts(scores, correct, ntruth, t))["f1"], float(t)))
        else:
            threshold = thresholds[str(label)]
        chosen[str(label)] = float(threshold)
        output[str(label)] = {"ap50": ap50, "ap75": average_precision(scores75, correct75, ntruth),
                              "truth_objects": ntruth, "threshold": float(threshold),
                              **rates(counts(scores, correct, ntruth, threshold))}
    return output, chosen
