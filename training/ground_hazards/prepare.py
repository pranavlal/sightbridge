#!/usr/bin/env python3
"""Audit local sources and build a road-grouped pothole mask manifest.

Bounding boxes and scene classes remain separate supervision; they are never
silently converted to segmentation masks or labelled as drop-offs.
"""
from collections import Counter
import json
from pathlib import Path
import random
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image

from acquire import DATA, digest, save


def prepare():
    output = DATA / "prepared"
    output.mkdir(parents=True, exist_ok=True)
    report = {"datasets": {}, "limitations": [
        "Public datasets do not constitute validation on SightBridge's camera mount.",
        "No descending direction inferred from generic stairs/curb labels.",
        "Pothole baseline excludes bounding-box-only sources.",
        "Brazilian road grouping is inferred from filenames, not verified sequence metadata.",
        "Exact duplicate checks do not remove all near-duplicate images."]}
    for name in ["brazil", "wet_potholes", "gutters", "rgbd_stairs", "sanpo", "rdd2022", "accessibility_barriers"]:
        root = DATA / "raw" / name
        files = [p for p in root.rglob("*") if p.is_file()]
        report["datasets"][name] = {"files": len(files), "extensions": dict(Counter(p.suffix for p in files))}

    rows, duplicates, errors = [], [], []
    seen = {}
    for image in sorted((DATA / "raw/brazil/images").rglob("*_RAW.jpg")):
        mask = image.with_name(image.name.replace("_RAW.jpg", "_POTHOLE.png"))
        try:
            with Image.open(image) as im:
                im.load()
                shape = im.size
            with Image.open(mask) as im:
                im.load()
                if im.size != shape:
                    raise ValueError("Image/mask dimensions differ")
                a = np.asarray(im.convert("L"))
                values = np.unique(a).tolist()
                if any(v not in (0, 255) for v in values):
                    raise ValueError(f"Nonbinary mask values: {values}")
                positive = int(np.count_nonzero(a))
            sha = digest(image)
            mask_sha = digest(mask)
            if sha in seen:
                if seen[sha]["mask_sha256"] != mask_sha:
                    raise ValueError("Duplicate image has conflicting pothole annotations")
                duplicates.append(str(image))
                continue
            parts = image.stem.split("_")
            group = "_".join(parts[1:3])  # state + highway, entire road kept together
            row = {"source": "brazil", "image": str(image.relative_to(DATA)),
                   "mask": str(mask.relative_to(DATA)), "sha256": sha, "mask_sha256": mask_sha,
                   "group": group, "width": shape[0], "height": shape[1],
                   "positive_pixels": positive, "has_pothole": positive > 0,
                   "annotation_type": "binary_pixel_mask", "license": "CC BY 4.0"}
            seen[sha] = row
            rows.append(row)
        except Exception as e:
            errors.append({"image": str(image), "error": str(e)})
    if errors or not rows:
        save(output / "audit_errors.json", errors)
        raise ValueError(f"Brazilian audit failed: {len(errors)} errors, {len(rows)} samples")
    groups = sorted({r["group"] for r in rows})
    random.Random(20261011).shuffle(groups)
    if len(groups) < 6:
        raise ValueError("Too few road groups for train/validation/test separation")
    count = max(1, round(len(groups) * .15))
    group_split = {g: "test" if i < count else "val" if i < count * 2 else "train"
                   for i, g in enumerate(groups)}
    for row in rows:
        row["split"] = group_split[row["group"]]
    for split in ["train", "val", "test"]:
        selected = [r for r in rows if r["split"] == split]
        if not selected or not any(r["has_pothole"] for r in selected):
            raise ValueError(f"Split {split} has no positive potholes; revise group split")
    (output / "brazil_potholes.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
    report["datasets"]["brazil"].update({"usable_images": len(rows), "pothole_images": sum(r["has_pothole"] for r in rows),
        "duplicates_removed": len(duplicates), "groups": len(groups), "split_images": dict(Counter(r["split"] for r in rows)),
        "split_positives": dict(Counter(r["split"] for r in rows if r["has_pothole"]))})
    save(output / "brazil_road_splits.json", group_split)

    # Verify YOLO boxes for gutter images; do not use bollards as hazard negatives.
    gutter_boxes, gutter_images, gutter_errors = 0, 0, []
    for image in (DATA / "raw/gutters").rglob("*"):
        if image.suffix.lower() not in (".jpg", ".jpeg", ".png") or "Uncovered Gutter" not in str(image):
            continue
        gutter_images += 1
        try:
            with Image.open(image) as im:
                im.load()
            for line in image.with_suffix(".txt").read_text().splitlines():
                values = [float(v) for v in line.split()]
                if len(values) != 5 or not all(np.isfinite(values)):
                    raise ValueError("Invalid YOLO box")
                _, x, y, w, h = values
                if not (0 <= x <= 1 and 0 <= y <= 1 and 0 < w <= 1 and 0 < h <= 1):
                    raise ValueError("YOLO coordinates outside normalized bounds")
                gutter_boxes += 1
        except Exception as e:
            gutter_errors.append({"image": str(image), "error": str(e)})
    report["datasets"]["gutters"].update({"images": gutter_images, "boxes": gutter_boxes, "errors": gutter_errors})
    wet_classes = Counter()
    quarantine = []
    for xml in (DATA / "raw/wet_potholes").rglob("*.xml"):
        names = [n.text for n in ET.parse(xml).findall(".//object/name")]
        wet_classes.update(names)
        if any(n != "pothole" for n in names):
            quarantine.append({"annotation": str(xml.relative_to(DATA)), "reason": "Unexpected class label",
                               "classes": names, "action": "Exclude image from future training until reviewed"})
    report["datasets"]["wet_potholes"]["box_classes"] = dict(wet_classes)
    report["datasets"]["wet_potholes"]["quarantined_images"] = len(quarantine)
    save(output / "quarantine.json", quarantine)

    rdd_classes, rdd_countries, rdd_errors = Counter(), {}, []
    for country in (DATA / "raw/rdd2022/countries").glob("*"):
        images = [p for p in country.rglob("*.jpg")]
        annotations = list(country.rglob("*.xml"))
        rdd_countries[country.name] = {"images": len(images), "annotation_files": len(annotations)}
        for xml in annotations:
            try:
                names = [n.text for n in ET.parse(xml).findall(".//object/name")]
                rdd_classes.update(names)
                if "D0w0" in names:
                    quarantine.append({"annotation": str(xml.relative_to(DATA)), "reason": "Unexpected RDD label D0w0",
                                       "classes": names, "action": "Exclude image from future training until reviewed"})
            except ET.ParseError as e:
                rdd_errors.append({"annotation": str(xml.relative_to(DATA)), "error": str(e)})
    report["datasets"]["rdd2022"].update({"countries": rdd_countries, "box_classes": dict(rdd_classes),
        "errors": rdd_errors, "test_policy": "Published test images have no labels; not hazard negatives"})
    save(output / "quarantine.json", quarantine)

    labelmap_path = DATA / "raw/sanpo/labelmap.json"
    if labelmap_path.exists():
        labelmap = json.loads(labelmap_path.read_text())
        classes = {int(v): k for k, v in labelmap.items()}
        presence, pixels, session_presence = Counter(), Counter(), {}
        split_root = DATA / "raw/sanpo/sanpo-real/splits"
        official_splits = {s: set((split_root / f"{s}_session_ids.txt").read_text().splitlines())
                           for s in ("train", "test") if (split_root / f"{s}_session_ids.txt").exists()}
        for mask in (DATA / "raw/sanpo").rglob("segmentation_masks/*.png"):
            image = mask.parent.parent / "video_frames" / mask.name
            with Image.open(image) as im:
                shape = im.size
                im.verify()
            with Image.open(mask) as im:
                if im.mode != "RGB" or im.size != shape:
                    raise ValueError(f"Invalid SANPO mask or dimensions: {mask}")
                # Confirmed against the official SANPO tensorflow_dataset.py loader:
                # R = semantic class; instance = G * 256 + B.
                semantic = np.asarray(im)[:, :, 0]
                counts = np.bincount(semantic.ravel(), minlength=max(classes) + 1)
            session = mask.relative_to(DATA / "raw/sanpo").parts[1]
            present = session_presence.setdefault(session, Counter())
            for class_id, count in enumerate(counts):
                if count and class_id not in classes:
                    raise ValueError(f"Unexpected SANPO class: {class_id}")
                if count:
                    label = classes[class_id]
                    presence[label] += 1
                    pixels[label] += int(count)
                    present[label] += 1
        report["datasets"]["sanpo"].update({"mask_encoding": "R=class; G*256+B=instance",
            "class_image_counts": dict(presence), "class_pixel_counts": dict(pixels),
            "session_class_image_counts": {s: dict(c) for s, c in session_presence.items()},
            "official_session_splits": {session: next((s for s, ids in official_splits.items() if session in ids), "unknown")
                                        for session in session_presence}})
    save(output / "audit.json", report)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    prepare()
