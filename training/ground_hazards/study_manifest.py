"""Build an immutable, source-aware detection comparison manifest."""
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import random
import re
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image
from scipy import ndimage

from acquire import DATA, digest, save

STUDY = DATA / "study"
CLASSES = {1: "pothole", 2: "stairs", 3: "curb", 4: "single_step", 5: "open_gutter"}


def components(mask, label, minimum=8):
    connected, _ = ndimage.label(mask)
    objects = ndimage.find_objects(connected)
    boxes = []
    h, w = mask.shape
    for index, region in enumerate(objects, 1):
        if region is None or np.count_nonzero(connected[region] == index) < minimum:
            continue
        y, x = region
        box = [x.start / w, y.start / h, x.stop / w, y.stop / h]
        if (box[2] - box[0]) * 320 < 2 or (box[3] - box[1]) * 320 < 2:
            continue
        boxes.append({"label": label, "box": box})
    return boxes


def row(image, source, supervised, group, split, objects, **extra):
    return {"image": str(image.relative_to(DATA)), "source": source, "supervised": supervised,
            "group": group, "split": split, "objects": objects, **extra}


def annotate_image(record):
    image = DATA / record["image"]
    with Image.open(image) as im:
        im = im.convert("RGB")
        width, height = im.size
        small = np.asarray(im.resize((32, 32), Image.Resampling.BILINEAR))
        gray = np.asarray(im.convert("L").resize((9, 8), Image.Resampling.BILINEAR))
    dhash = np.packbits(gray[:, 1:] > gray[:, :-1]).tobytes().hex()
    record.update({"sha256": digest(image), "dhash": dhash, "width": width, "height": height})
    record["_thumbnail"] = small
    for obj in record["objects"]:
        b = obj["box"]
        if len(b) != 4 or not all(np.isfinite(b)) or not (0 <= b[0] < b[2] <= 1 and 0 <= b[1] < b[3] <= 1):
            raise ValueError(f"Invalid box in {record['image']}: {b}")
    return record


def build():
    protocol = {"classes": CLASSES, "arms": ["others", "barriers", "combined"], "seeds": [17, 29, 43],
        "model": "COCO-pretrained SSDLite320 MobileNetV3; frozen backbone; trained classification/regression heads",
        "training_updates": 1000, "batch_size": 12, "checkpoint_every": 200,
        "combined_sampling": "40% accessibility; 60% other sources, balanced by source",
        "primary_comparison": "combined minus others on pothole, stairs, curb; gutter reported descriptively only",
        "meaningful_gain_rule": "At least 5 percentage points in macro detection F1, paired 95% CI excludes zero",
        "transfer_rule": "Also inspect non-barrier holdout gains/regressions; no deployment claim from in-domain gains",
        "iou": [.5, .75], "score_threshold": "per-class selected on validation F1 only; frozen before test",
        "uncertainty": "paired bootstrap of training seeds and image/near-duplicate groups, stratified by source",
        "annotation_policy": "Only score/supervise classes annotated by each source; no boxes fabricated from scene classes",
        "limitations": ["Annotation task batches are proxies, not verified locations, for Accessibility Barriers",
            "Exact hashes plus conservative perceptual matching do not prove all near duplicates removed",
            "Frames/stereo views grouped; stair train/val scene prefixes held together",
            "Generic stairs/curbs do not establish drop direction or safe walking",
            "Frozen backbone and bounded RDD sample; this is a controlled screening experiment",
            "Earlier Brazilian exploratory test was used previously; not a pristine deployment acceptance set",
            "No time-to-edge, real walking false-alarm rate, or device validation in this study",
            "Only two unique gutter test images after deduplication; insufficient for a reliable gain estimate"]}
    save(STUDY / "protocol.json", protocol)  # committed before training or test scoring
    records = []
    for old in map(json.loads, (DATA / "prepared/brazil_potholes.jsonl").read_text().splitlines()):
        with Image.open(DATA / old["mask"]) as im:
            objects = components(np.asarray(im.convert("L")) > 0, 1)
        records.append(row(DATA / old["image"], "brazil", [1], "brazil:" + old["group"], old["split"], objects))

    # RDD sample covers each country/camera; reserve entire countries as external tests.
    # Never use the official unlabelled test set as negative supervision.
    for country in sorted((DATA / "raw/rdd2022/countries").iterdir()):
        xmls = sorted(country.rglob("*.xml"))
        positives, negatives = [], []
        for xml in xmls:
            root = ET.parse(xml).getroot()
            names = [n.text for n in root.findall("object/name")]
            if "D0w0" in names:
                continue
            width, height = float(root.findtext("size/width")), float(root.findtext("size/height"))
            objects = []
            for obj in root.findall("object"):
                if obj.findtext("name") != "D40":
                    continue
                b = obj.find("bndbox")
                coords = [float(b.findtext(k)) for k in ("xmin", "ymin", "xmax", "ymax")]
                coords = [max(0., min(1., v / (width if i % 2 == 0 else height))) for i, v in enumerate(coords)]
                if coords[0] < coords[2] and coords[1] < coords[3]:
                    objects.append({"label": 1, "box": coords})
            candidates = list(xml.parents[1].glob("images/" + xml.stem + ".jpg"))
            if not candidates:
                # Pascal VOC layout: annotations/xmls and images are siblings of annotations.
                candidates = list(xml.parents[2].glob("images/" + xml.stem + ".jpg"))
            if len(candidates) != 1:
                raise ValueError(f"RDD image not found: {xml}")
            item = (candidates[0], objects)
            (positives if objects else negatives).append(item)
        rng = random.Random(20261011)
        rng.shuffle(positives)
        rng.shuffle(negatives)
        split = "test" if country.name in ("India", "United_States") else "val" if country.name == "Czech" else "train"
        selected = positives[:160] + negatives[:160]
        for image, objects in selected:
            records.append(row(image, "rdd2022", [1], "rdd:" + country.name, split, objects, country=country.name))

    wet = DATA / "raw/wet_potholes"
    for xml in sorted(wet.rglob("*.xml")):
        root = ET.parse(xml).getroot()
        if any(n.text != "pothole" for n in root.findall("object/name")):
            continue
        width, height = float(root.findtext("size/width")), float(root.findtext("size/height"))
        objects = []
        for obj in root.findall("object"):
            b = obj.find("bndbox")
            coords = [float(b.findtext(k)) for k in ("xmin", "ymin", "xmax", "ymax")]
            coords = [max(0., min(1., v / (width if i % 2 == 0 else height))) for i, v in enumerate(coords)]
            if coords[0] < coords[2] and coords[1] < coords[3]:
                objects.append({"label": 1, "box": coords})
        image = xml.parent.parent / "IMG" / (xml.stem + ".jpg")
        records.append(row(image, "wet_potholes", [1], "wet:" + xml.stem, "pending", objects))

    for image in sorted((DATA / "raw/gutters").rglob("*.jpeg")):
        if "Uncovered Gutter" not in str(image):
            continue
        objects = []
        for line in image.with_suffix(".txt").read_text().splitlines():
            _, x, y, w, h = map(float, line.split())
            box = [max(0., x - w / 2), max(0., y - h / 2), min(1., x + w / 2), min(1., y + h / 2)]
            objects.append({"label": 5, "box": box})
        records.append(row(image, "gutters", [5], "gutter:" + image.stem, "pending", objects))

    # Stereo and neighbouring frames stay in their entire scene family.
    for image in sorted((DATA / "raw/rgbd_stairs").rglob("images/*")):
        if image.suffix.lower() not in (".jpg", ".png") or "/test/" in str(image):
            continue  # official test lacks step-surface masks
        mask = image.parent.parent / "segmentations" / (image.stem + ".png")
        with Image.open(mask) as im:
            a = np.asarray(im.convert("L")) > 0
        ys, xs = np.where(a)
        objects = [] if len(xs) == 0 else [{"label": 2, "box": [xs.min() / a.shape[1], ys.min() / a.shape[0],
                                                          (xs.max() + 1) / a.shape[1], (ys.max() + 1) / a.shape[0]]}]
        family = "park" if image.stem.startswith("park") else "night" if "night" in image.stem else "sh_color" if image.stem.startswith("sh_") else "color"
        split = "test" if family == "sh_color" else "val" if family == "color" else "train"
        records.append(row(image, "rgbd_stairs", [2], "rgbd:" + family, split, objects))

    sanpo = DATA / "raw/sanpo"
    split_root = sanpo / "sanpo-real/splits"
    test_sessions = set((split_root / "test_session_ids.txt").read_text().splitlines())
    pilot = json.loads((DATA / "metadata/sanpo_pilot.json").read_text())
    train_sessions = sorted(s["session"].rstrip("/").split("/")[-1] for s in pilot["sessions"] if s["session"].rstrip("/").split("/")[-1] not in test_sessions)
    # Keep the original pilot immutable when later acquisition adds sessions/frames.
    pilot_masks = {o["name"].removeprefix("sanpo_dataset/v0/")
                   for session in pilot["sessions"] for o in session["objects"]
                   if "/segmentation_masks/" in o["name"] and o["name"].endswith(".png")}
    # One complete official-training session reserved for validation.
    for mask in sorted(sanpo.rglob("segmentation_masks/*.png")):
        if str(mask.relative_to(sanpo)) not in pilot_masks:
            continue
        with Image.open(mask) as im:
            a = np.asarray(im)[:, :, 0]
        objects = components(a == 2, 3, 50) + components(a == 15, 2, 50)
        image = mask.parent.parent / "video_frames" / mask.name
        session = mask.relative_to(sanpo).parts[1]
        split = "test" if session in test_sessions else "val" if session == train_sessions[-1] else "train"
        records.append(row(image, "sanpo", [2, 3], "sanpo:" + session, split, objects))

    barrier_xml = next((DATA / "raw/accessibility_barriers").rglob("*.xml"))
    for im in ET.parse(barrier_xml).getroot().findall("image"):
        width, height = float(im.attrib["width"]), float(im.attrib["height"])
        task = im.attrib["task_id"]
        objects = []
        for b in im.findall("box"):
            kind = b.attrib["label"]
            attributes = {a.attrib["name"]: a.text for a in b.findall("attribute")}
            label = 2 if kind == "stair" else 3 if kind == "step" and attributes.get("type") == "curb" else 4 if kind == "step" else None
            if label is None:
                continue
            box = [float(b.attrib[k]) / (width if i % 2 == 0 else height) for i, k in enumerate(("xtl", "ytl", "xbr", "ybr"))]
            box = [max(0., min(1., v)) for v in box]
            if box[0] < box[2] and box[1] < box[3]:
                objects.append({"label": label, "box": box, "attributes": attributes})
        split = "test" if task in ("7", "8") else "val" if task == "9" else "train"
        records.append(row(barrier_xml.parent / im.attrib["name"], "barriers", [2, 3, 4], "barrier_task:" + task, split, objects))

    print("Auditing", len(records), "images", flush=True)
    with ThreadPoolExecutor(max_workers=4) as pool:
        records = list(pool.map(annotate_image, records))

    # Link exact copies and conservative near copies before split assignment.
    parent = list(range(len(records)))
    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    def union(a, b):
        parent[find(b)] = find(a)
    by_hash, by_dhash = {}, defaultdict(list)
    near_links = 0
    for i, r in enumerate(records):
        if r["sha256"] in by_hash:
            union(i, by_hash[r["sha256"]])
        by_hash[r["sha256"]] = i
        for j in by_dhash[r["dhash"]]:
            q = records[j]
            ratio = (r["width"] / r["height"]) / (q["width"] / q["height"])
            if .97 <= ratio <= 1.03 and np.abs(r["_thumbnail"].astype(float) - q["_thumbnail"]).mean() <= 5:
                union(i, j)
                near_links += 1
        by_dhash[r["dhash"]].append(i)
    clusters = defaultdict(list)
    for i in range(len(records)):
        clusters[find(i)].append(i)
    excluded = []
    for indices in clusters.values():
        explicit = {records[i]["split"] for i in indices} - {"pending"}
        priority = "test" if "test" in explicit else "val" if "val" in explicit else "train" if "train" in explicit else None
        cluster_id = min(records[i]["sha256"] for i in indices)
        if priority is None:
            value = int(hashlib.sha256(cluster_id.encode()).hexdigest()[:8], 16) % 100
            priority = "test" if value < 20 else "val" if value < 35 else "train"
        for i in indices:
            r = records[i]
            r["evaluation_cluster"] = cluster_id
            if r["split"] != "pending" and r["split"] != priority:
                # Preserve official/source holdouts, exclude conflicting train copies.
                r["excluded"] = "exact/perceptual duplicate linked to higher-priority holdout"
                excluded.append({"image": r["image"], "reason": r["excluded"]})
            else:
                r["split"] = priority
    clean = []
    exact_seen = set()
    for r in records:
        del r["_thumbnail"]
        if r.get("excluded"):
            continue
        key = (r["source"], r["sha256"])
        if key in exact_seen:
            excluded.append({"image": r["image"], "reason": "same-source exact duplicate"})
            continue
        exact_seen.add(key)
        r["id"] = hashlib.sha256((r["source"] + ":" + r["image"]).encode()).hexdigest()[:20]
        clean.append(r)
    for first, second in (("train", "val"), ("train", "test"), ("val", "test")):
        assert not ({r["evaluation_cluster"] for r in clean if r["split"] == first} & {r["evaluation_cluster"] for r in clean if r["split"] == second})
    summary = {"images": len(clean), "near_duplicate_links": near_links, "excluded": excluded, "sources": {}}
    for source in sorted({r["source"] for r in clean}):
        rs = [r for r in clean if r["source"] == source]
        summary["sources"][source] = {"splits": dict(Counter(r["split"] for r in rs)),
            "boxes": {split: dict(Counter(CLASSES[o["label"]] for r in rs if r["split"] == split for o in r["objects"])) for split in ("train", "val", "test")}}
    (STUDY / "manifest.jsonl").write_text("".join(json.dumps(r) + "\n" for r in clean))
    save(STUDY / "manifest_audit.json", summary)
    save(STUDY / "manifest_receipt.json", {"sha256": digest(STUDY / "manifest.jsonl"), "protocol_sha256": digest(STUDY / "protocol.json")})
    print(json.dumps({k: v for k, v in summary.items() if k != "excluded"}, indent=2), flush=True)


if __name__ == "__main__":
    build()
