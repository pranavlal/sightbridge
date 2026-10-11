#!/usr/bin/env python3
"""CPU-capable exploratory pothole segmentation, never a deployment artifact."""
import argparse
import json
from pathlib import Path
import random
import time

import numpy as np
from PIL import Image
import torch
from torch import nn
from torch.nn import functional as F
from torch.utils.data import Dataset, DataLoader

from acquire import DATA, digest, save


class Potholes(Dataset):
    def __init__(self, rows, size):
        self.rows, self.size = rows, size

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, index):
        row = self.rows[index]
        with Image.open(DATA / row["image"]) as image:
            rgb = np.asarray(image.convert("RGB").resize((self.size, self.size), Image.Resampling.BILINEAR)).copy()
        with Image.open(DATA / row["mask"]) as mask:
            labels = np.asarray(mask.convert("L").resize((self.size, self.size), Image.Resampling.NEAREST)).copy()
        return torch.from_numpy(rgb).permute(2, 0, 1).float() / 255, torch.from_numpy(labels > 0).unsqueeze(0).float()


def block(a, b, stride=1):
    return nn.Sequential(nn.Conv2d(a, a, 3, stride=stride, padding=1, groups=a), nn.ReLU(),
                         nn.Conv2d(a, b, 1), nn.ReLU())


class Baseline(nn.Module):
    def __init__(self):
        super().__init__()
        self.first = nn.Sequential(nn.Conv2d(3, 16, 3, padding=1), nn.ReLU(), block(16, 24))
        self.second = nn.Sequential(block(24, 48, 2), block(48, 48))
        self.third = nn.Sequential(block(48, 64, 2), block(64, 64))
        self.decode_second = block(112, 48)
        self.decode_first = nn.Sequential(block(72, 24), nn.Conv2d(24, 1, 1))

    def forward(self, x):
        a = self.first(x)
        b = self.second(a)
        c = self.third(b)
        c = F.interpolate(c, size=b.shape[-2:], mode="bilinear", align_corners=False)
        b = self.decode_second(torch.cat([b, c], dim=1))
        b = F.interpolate(b, size=a.shape[-2:], mode="bilinear", align_corners=False)
        return self.decode_first(torch.cat([a, b], dim=1))


@torch.no_grad()
def evaluate(model, loader):
    model.eval()
    tp = fp = fn = 0
    positive_images = negative_images = 0
    for x, y in loader:
        predicted = model(x).sigmoid() >= .5
        actual = y.bool()
        tp += (predicted & actual).sum().item()
        fp += (predicted & ~actual).sum().item()
        fn += (~predicted & actual).sum().item()
        positive_images += actual.flatten(1).any(1).sum().item()
        negative_images += (~actual.flatten(1).any(1)).sum().item()
    return {"pixel_iou": tp / max(1, tp + fp + fn), "pixel_precision": tp / max(1, tp + fp),
            "pixel_recall": tp / max(1, tp + fn), "tp_pixels": tp, "fp_pixels": fp, "fn_pixels": fn,
            "positive_images_after_resize": positive_images, "negative_images_after_resize": negative_images}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--size", type=int, default=160)
    parser.add_argument("--batch-size", type=int, default=12)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--evaluate-test", action="store_true", help="Explicitly evaluate the held-out exploratory test split")
    args = parser.parse_args()
    torch.set_num_threads(args.threads)
    torch.manual_seed(20261011)
    random.seed(20261011)
    np.random.seed(20261011)
    manifest = DATA / "prepared/brazil_potholes.jsonl"
    rows = [json.loads(line) for line in manifest.read_text().splitlines()]
    splits = {s: [r for r in rows if r["split"] == s] for s in ("train", "val", "test")}
    for a, b in (("train", "val"), ("train", "test"), ("val", "test")):
        assert not ({r["group"] for r in splits[a]} & {r["group"] for r in splits[b]})
        assert not ({r["sha256"] for r in splits[a]} & {r["sha256"] for r in splits[b]})
    loaders = {s: DataLoader(Potholes(rs, args.size), batch_size=args.batch_size, shuffle=s == "train") for s, rs in splits.items()}
    run = Path(__file__).resolve().parent / "runs" / time.strftime("pothole-%Y%m%d-%H%M%S")
    run.mkdir(parents=True)
    save(run / "config.json", {**vars(args), "seed": 20261011, "torch": torch.__version__, "device": "cpu",
        "manifest_sha256": digest(manifest), "architecture": "depthwise encoder-decoder with skip connections",
        "loss": "frequency-weighted BCE and batch foreground Dice", "scope": "Brazil-only exploratory binary pothole segmentation",
        "limitations": "Untrained from scratch initially; no drop-off class, mobile validation, or local-route evaluation."})
    model = Baseline()
    optimizer = torch.optim.Adam(model.parameters(), lr=1e-3)
    positive = sum(r["positive_pixels"] for r in splits["train"])
    total = sum(r["width"] * r["height"] for r in splits["train"])
    pos_weight = torch.tensor([min(1000., (total - positive) / max(1, positive))])
    history, best = [], -1.
    started = time.time()
    for epoch in range(args.epochs):
        model.train()
        total_loss = steps = 0
        for x, y in loaders["train"]:
            optimizer.zero_grad()
            logits = model(x)
            p = logits.sigmoid()
            dice = 1 - (2 * (p * y).sum() + 1) / (p.sum() + y.sum() + 1)
            loss = F.binary_cross_entropy_with_logits(logits, y, pos_weight=pos_weight) + dice
            loss.backward()
            optimizer.step()
            total_loss += loss.item()
            steps += 1
        metrics = evaluate(model, loaders["val"])
        result = {"epoch": epoch + 1, "train_loss": total_loss / steps, "validation": metrics}
        history.append(result)
        if metrics["pixel_iou"] > best:
            best = metrics["pixel_iou"]
            torch.save(model.state_dict(), run / "best.pt")
        save(run / "history.json", history)
        print(json.dumps(result), flush=True)
    model.load_state_dict(torch.load(run / "best.pt", weights_only=True))
    save(run / "result.json", {"validation": evaluate(model, loaders["val"]),
        "test": evaluate(model, loaders["test"]) if args.evaluate_test else "not evaluated",
        "elapsed_seconds": time.time() - started,
        "parameter_count": sum(p.numel() for p in model.parameters()), "deployable": False,
        "reason": "Exploratory baseline only; evaluate diverse/local hazards before Android integration."})
    print("Baseline complete:", run, flush=True)


if __name__ == "__main__":
    main()
