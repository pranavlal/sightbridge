"""Compute-matched dataset ablation on a frozen pretrained mobile backbone."""
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
import copy
from functools import partial
import json
from pathlib import Path
import time

import numpy as np
from PIL import Image
import torch
from torch import nn
from torch.nn import functional as F
import torchvision
from torchvision.models.detection import ssdlite320_mobilenet_v3_large
from torchvision.models.detection.image_list import ImageList
from torchvision.models.detection.ssdlite import SSDLiteClassificationHead
from torchvision.ops import box_iou

from acquire import DATA, digest, save
from detection_metrics import summarize
from study_manifest import STUDY


def pretrained():
    model = ssdlite320_mobilenet_v3_large(weights=None, weights_backbone=None)
    model.load_state_dict(torch.load(STUDY / "pretrained_ssdlite.pth", weights_only=True))
    model.eval()
    model.score_thresh = .01
    model.detections_per_img = 100
    return model


def image_tensor(record):
    with Image.open(DATA / record["image"]) as im:
        array = np.asarray(im.convert("RGB").resize((320, 320), Image.Resampling.BILINEAR)).copy()
    return torch.from_numpy(array).permute(2, 0, 1).float() / 255


@torch.no_grad()
def cache_features(model, records, batch_size=12):
    cache = STUDY / "features"
    cache.mkdir(exist_ok=True)
    recipe = {"weights_sha256": digest(STUDY / "pretrained_ssdlite.pth"),
              "torchvision": torchvision.__version__, "size": 320, "normalization": "SSD mean/std; RGB bilinear resize", "precision": "float16 storage, float32 head"}
    marker = cache / "recipe.json"
    if marker.exists() and json.loads(marker.read_text()) != recipe:
        raise ValueError("Feature cache recipe changed; use a fresh cache directory")
    save(marker, recipe)
    missing = [r for r in records if not (cache / (r["id"] + ".pt")).exists()]
    print("Feature cache:", len(missing), "new images", flush=True)
    mean = torch.tensor(model.transform.image_mean)[None, :, None, None]
    std = torch.tensor(model.transform.image_std)[None, :, None, None]
    with ThreadPoolExecutor(max_workers=4) as pool:
        for index in range(0, len(missing), batch_size):
            batch = missing[index:index + batch_size]
            images = torch.stack(list(pool.map(image_tensor, batch)))
            features = list(model.backbone((images - mean) / std).values())
            for i, record in enumerate(batch):
                path = cache / (record["id"] + ".pt")
                tmp = path.with_suffix(".tmp")
                torch.save({"sha256": record["sha256"], "features": [f[i].half().clone() for f in features]}, tmp)
                tmp.replace(path)
            if index % 240 == 0:
                print("Cached", min(index + batch_size, len(missing)), "/", len(missing), flush=True)


def load_features(records):
    result = {}
    for index, record in enumerate(records):
        value = torch.load(STUDY / "features" / (record["id"] + ".pt"), weights_only=True)
        if value["sha256"] != record["sha256"]:
            raise ValueError("Cached features do not match input image")
        result[record["id"]] = value["features"]
        if index % 1000 == 0:
            print("Loaded cache", index, flush=True)
    return result


def make_head(base, seed):
    torch.manual_seed(seed)
    head = copy.deepcopy(base.head)
    classification = SSDLiteClassificationHead([672, 480, 512, 256, 256, 128], [6] * 6, 6,
                                              partial(nn.BatchNorm2d, eps=.001, momentum=.03))
    # Reuse COCO depthwise feature filters; initialize only the final hazard logits.
    original = head.classification_head.state_dict()
    current = classification.state_dict()
    transferable = {k: v for k, v in original.items() if k in current and v.shape == current[k].shape}
    classification.load_state_dict(transferable, strict=False)
    head.classification_head = classification
    head.eval()  # frozen normalization statistics; gradients still update head weights
    return head


def batch_features(batch, features):
    return [torch.stack([features[r["id"]][level] for r in batch]).float() for level in range(6)]


def targets_and_matches(base, records, anchors):
    result = {}
    for r in records:
        boxes = torch.tensor([o["box"] for o in r["objects"]], dtype=torch.float32).reshape(-1, 4) * 320
        labels = torch.tensor([o["label"] for o in r["objects"]], dtype=torch.long)
        matched = base.proposal_matcher(box_iou(boxes, anchors)) if len(boxes) else torch.full((len(anchors),), -1, dtype=torch.long)
        valid = torch.tensor([c == 0 or c in r["supervised"] for c in range(6)], dtype=torch.bool)
        result[r["id"]] = {"boxes": boxes, "labels": labels, "matched": matched, "valid": valid}
    return result


def loss(base, output, batch, targets, anchors):
    regression = output["bbox_regression"]
    logits = output["cls_logits"]
    class_targets = []
    regression_losses = []
    valid = []
    foreground_count = 0
    for i, record in enumerate(batch):
        t = targets[record["id"]]
        foreground = torch.where(t["matched"] >= 0)[0]
        matched = t["matched"][foreground]
        foreground_count += len(foreground)
        if len(foreground):
            encoded = base.box_coder.encode_single(t["boxes"][matched], anchors[foreground])
            regression_losses.append(F.smooth_l1_loss(regression[i, foreground], encoded, reduction="sum"))
        labels = torch.zeros(len(anchors), dtype=torch.long)
        labels[foreground] = t["labels"][matched]
        class_targets.append(labels)
        valid.append(t["valid"])
    class_targets = torch.stack(class_targets)
    valid = torch.stack(valid)
    logits = logits.masked_fill(~valid[:, None, :], -10000.)
    classification = F.cross_entropy(logits.reshape(-1, 6), class_targets.reshape(-1), reduction="none").reshape(class_targets.shape)
    positive = class_targets > 0
    negative_loss = classification.detach().clone()
    negative_loss[positive] = -float("inf")
    _, indices = negative_loss.sort(1, descending=True)
    # Explicit negatives are trained even on images with no annotated hazard.
    nnegative = (positive.sum(1, keepdim=True) * 3).clamp(min=24, max=1000)
    negative = (indices.argsort(1) < nnegative) & ~positive
    normalizer = max(24, foreground_count)
    box_loss = sum(regression_losses) / normalizer if regression_losses else regression.sum() * 0
    class_loss = (classification[positive].sum() + classification[negative].sum()) / normalizer
    return box_loss + class_loss


@torch.no_grad()
def predict(base, head, records, features, anchors, active, batch_size=16):
    head.eval()
    predictions = {}
    allowed = torch.tensor([c == 0 or c in active for c in range(6)], dtype=torch.bool)
    for index in range(0, len(records), batch_size):
        batch = records[index:index + batch_size]
        output = head(batch_features(batch, features))
        output["cls_logits"] = output["cls_logits"].masked_fill(~allowed[None, None, :], -10000.)
        detected = base.postprocess_detections(output, [anchors] * len(batch), [(320, 320)] * len(batch))
        for record, prediction in zip(batch, detected):
            predictions[record["id"]] = {"boxes": (prediction["boxes"] / 320).tolist(),
                                           "scores": prediction["scores"].tolist(), "labels": prediction["labels"].tolist()}
    return predictions


def choose_batch(pool, arm, rng, size):
    sources = sorted(pool)
    others = [s for s in sources if s != "barriers"]
    selected = []
    for _ in range(size):
        source = "barriers" if arm == "barriers" or (arm == "combined" and rng.random() < .4) else rng.choice(others)
        selected.append(pool[source][rng.integers(len(pool[source]))])
    return selected


def train_arm(base, records, features, targets, anchors, arm, seed, protocol):
    directory = STUDY / "runs" / f"{arm}-{seed}"
    directory.mkdir(parents=True, exist_ok=True)
    receipt = {"manifest_sha256": digest(STUDY / "manifest.jsonl"), "protocol_sha256": digest(STUDY / "protocol.json")}
    if (directory / "complete.json").exists():
        if json.loads((directory / "complete.json").read_text())["receipt"] != receipt:
            raise ValueError("Completed run belongs to different manifest/protocol")
        print("Already complete", arm, seed, flush=True)
        return
    started = time.time()
    active = [2, 3, 4] if arm == "barriers" else [1, 2, 3, 5] if arm == "others" else [1, 2, 3, 4, 5]
    training = [r for r in records if r["split"] == "train" and (r["source"] == "barriers" if arm == "barriers" else r["source"] != "barriers" if arm == "others" else True)]
    validation = [r for r in records if r["split"] == "val"]
    testing = [r for r in records if r["split"] == "test"]
    pool = defaultdict(list)
    for r in training:
        pool[r["source"]].append(r)
    head = make_head(base, seed)
    initial_sha = directory / "initial.pt"
    torch.save(head.state_dict(), initial_sha)
    save(directory / "config.json", {"arm": arm, "seed": seed, "active": active, "receipt": receipt,
        "train_images": len(training), "initial_sha256": digest(initial_sha), "torch": torch.__version__,
        "torchvision": torchvision.__version__, "device": "cpu"})
    optimizer = torch.optim.AdamW(head.parameters(), lr=.0005, weight_decay=.0001)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=protocol["training_updates"], eta_min=.00005)
    rng = np.random.default_rng(seed)
    best, history, losses = -1., [], []
    for step in range(1, protocol["training_updates"] + 1):
        head.eval()
        batch = choose_batch(pool, arm, rng, protocol["batch_size"])
        optimizer.zero_grad(set_to_none=True)
        output = head(batch_features(batch, features))
        value = loss(base, output, batch, targets, anchors)
        if not torch.isfinite(value):
            raise ValueError("Non-finite detector training loss")
        value.backward()
        nn.utils.clip_grad_norm_(head.parameters(), 10.)
        optimizer.step()
        scheduler.step()
        losses.append(value.item())
        if step % 50 == 0:
            print(arm, seed, "step", step, "loss", round(float(np.mean(losses[-50:])), 4), flush=True)
        if step % protocol["checkpoint_every"] == 0:
            predictions = predict(base, head, validation, features, anchors, active)
            selection_labels = [2, 3, 4] if arm == "barriers" else [1, 2, 3, 5]
            metrics, thresholds = summarize(validation, predictions, selection_labels)
            score = float(np.mean([m["ap50"] for m in metrics.values() if m["ap50"] is not None]))
            result = {"step": step, "loss": float(np.mean(losses[-protocol["checkpoint_every"]:])), "validation_map50": score}
            history.append(result)
            print("Validation", arm, seed, json.dumps(result), flush=True)
            if score > best:
                best = score
                torch.save(head.state_dict(), directory / "best.pt")
            save(directory / "history.json", history)
    head.load_state_dict(torch.load(directory / "best.pt", weights_only=True))
    val_predictions = predict(base, head, validation, features, anchors, active)
    validation_metrics, thresholds = summarize(validation, val_predictions, active)
    # All hyperparameters/checkpoint/thresholds now frozen before test inference.
    save(directory / "validation.json", {"metrics": validation_metrics, "thresholds": thresholds})
    test_predictions = predict(base, head, testing, features, anchors, active)
    save(directory / "test_predictions.json", test_predictions)
    test_metrics, _ = summarize(testing, test_predictions, active, thresholds)
    source_metrics = {}
    for source in sorted({r["source"] for r in testing}):
        subset = [r for r in testing if r["source"] == source]
        labels = [label for label in active if any(label in r["supervised"] for r in subset)]
        source_metrics[source], _ = summarize(subset, test_predictions, labels, thresholds)
    save(directory / "complete.json", {"receipt": receipt, "elapsed_seconds": time.time() - started,
        "test": test_metrics, "sources": source_metrics, "deployable": False})
    print("Complete", arm, seed, "seconds", round(time.time() - started), flush=True)


def main():
    torch.set_num_threads(4)
    torch.set_num_interop_threads(1)
    records = [json.loads(line) for line in (STUDY / "manifest.jsonl").read_text().splitlines()]
    protocol = json.loads((STUDY / "protocol.json").read_text())
    save(STUDY / "status.json", {"state": "feature_caching", "started": time.time()})
    model = pretrained()
    cache_features(model, records)
    features = load_features(records)
    sample = [f.unsqueeze(0).float() for f in features[records[0]["id"]]]
    anchors = model.anchor_generator(ImageList(torch.zeros(1, 3, 320, 320), [(320, 320)]), sample)[0]
    targets = targets_and_matches(model, records, anchors)
    for seed in protocol["seeds"]:
        for arm in protocol["arms"]:
            save(STUDY / "status.json", {"state": "training", "arm": arm, "seed": seed, "updated": time.time()})
            train_arm(model, records, features, targets, anchors, arm, seed, protocol)
    save(STUDY / "status.json", {"state": "models_complete", "finished": time.time()})
    print("All nine detector runs complete", flush=True)


if __name__ == "__main__":
    main()
