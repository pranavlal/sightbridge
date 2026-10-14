#!/usr/bin/env python3
"""Acquire official hazard datasets; retain provenance and verify archives.

Large downloads are resumable. SANPO is deliberately a bounded pilot subset.
ExoNet requires the user's IEEE DataPort session and is never scraped around it.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
from urllib.parse import quote
import zipfile

import requests

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data/ground_hazards"


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, indent=2) + "\n")
    tmp.replace(path)


def get_json(url, params=None):
    for attempt in range(4):
        try:
            r = requests.get(url, params=params, timeout=(20, 90))
            r.raise_for_status()
            return r.json()
        except (requests.RequestException, ValueError):
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)


def digest(path, algorithm="sha256"):
    h = hashlib.new(algorithm)
    with path.open("rb") as f:
        for block in iter(lambda: f.read(8 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def download(url, path, size=None, checksum=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    marker = path.with_suffix(path.suffix + ".verified.json")
    if marker.exists() and path.exists():
        receipt = json.loads(marker.read_text())
        if path.stat().st_size == receipt["bytes"] and digest(path) == receipt["sha256"]:
            return receipt
    if shutil.disk_usage(DATA).free < 20 * 1024**3:
        raise RuntimeError("Less than 20 GiB free; stopping acquisition")
    part = path.with_suffix(path.suffix + ".part")
    if not (path.exists() and size is not None and path.stat().st_size == size):
        subprocess.run(["curl", "-fLsS", "--connect-timeout", "30", "--retry", "5",
                        "--retry-delay", "3", "--speed-limit", "1024", "--speed-time", "120",
                        "-C", "-", url, "-o", str(part)], check=True)
        if size is not None and part.stat().st_size != size:
            raise ValueError(f"Size mismatch: {path.name}")
        if checksum:
            alg, expected = checksum.split(":", 1)
            if digest(part, alg) != expected:
                raise ValueError(f"Checksum mismatch: {path.name}")
        part.replace(path)
    elif checksum:
        alg, expected = checksum.split(":", 1)
        if digest(path, alg) != expected:
            raise ValueError(f"Checksum mismatch: {path.name}")
    receipt = {"url": url, "bytes": path.stat().st_size, "sha256": digest(path),
               "source_checksum": checksum}
    save(marker, receipt)
    return receipt


def extract(path, target):
    target.mkdir(parents=True, exist_ok=True)
    marker = target / ".extracted.json"
    if marker.exists():
        return
    with zipfile.ZipFile(path) as z:
        if shutil.disk_usage(DATA).free < sum(i.file_size for i in z.infolist()) + 20 * 1024**3:
            raise RuntimeError("Insufficient free space for archive extraction")
        for i in z.infolist():
            if not (target / i.filename).resolve().is_relative_to(target.resolve()):
                raise ValueError("Unsafe archive path")
            if (i.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Archive contains a symbolic link")
        bad = z.testzip()
        if bad:
            raise ValueError(f"Archive CRC failure: {bad}")
        z.extractall(target)
        save(marker, {"archive": str(path), "sha256": digest(path), "members": len(z.infolist())})


def mendeley(name, dataset, version):
    meta = get_json(f"https://data.mendeley.com/public-api/datasets/{dataset}/snapshot/{version}")
    save(DATA / "metadata" / f"{name}.json", meta)
    # Complete official archive, preserving all original folders/annotations.
    path = DATA / "archives" / f"{name}.zip"
    receipt = download(f"https://data.mendeley.com/public-api/zip/{dataset}/download/{version}", path)
    extract(path, DATA / "raw" / name)
    if name == "brazil":
        nested = list((DATA / "raw" / name / "Dataset").glob("*.zip"))
        if len(nested) != 1:
            raise ValueError("Expected one Brazilian dataset archive inside the release")
        extract(nested[0], DATA / "raw" / name / "images")
    return receipt


def sanpo(sessions=8, frames=48, metadata_name="sanpo_pilot.json", excluded_sessions=(), training_only=False):
    api = "https://storage.googleapis.com/storage/v1/b/gresearch/o"

    def listing(prefix, delimiter=None):
        params = {"prefix": prefix, "maxResults": 1000}
        if delimiter:
            params["delimiter"] = delimiter
        result = {"items": [], "prefixes": []}
        while True:
            page = get_json(api, params)
            for key in result:
                result[key].extend(page.get(key, []))
            if not page.get("nextPageToken"):
                return result
            params["pageToken"] = page["nextPageToken"]

    def fetch(obj):
        relative = obj["name"].removeprefix("sanpo_dataset/v0/")
        checksum = "md5:" + base64.b64decode(obj["md5Hash"]).hex()
        return download("https://storage.googleapis.com/gresearch/" + quote(obj["name"], safe="/"),
                        DATA / "raw/sanpo" / relative, int(obj["size"]), checksum)

    root = listing("sanpo_dataset/v0/", "/")
    for obj in root["items"]:
        if obj["name"].endswith(".json"):
            fetch(obj)
    overview = listing("sanpo_dataset/v0/sanpo-real/", "/")
    save(DATA / "metadata/sanpo_real_index.json", overview)
    for obj in listing("sanpo_dataset/v0/sanpo-real/splits/")["items"]:
        if obj["name"].endswith(".txt"):
            fetch(obj)
    selected = []
    # Pilot only: deterministic spread across session IDs, annotated chest frames.
    candidates = [p for p in overview["prefixes"] if not p.endswith("/splits/")]
    excluded = set(excluded_sessions)
    official_train = set((DATA / "raw/sanpo/sanpo-real/splits/train_session_ids.txt").read_text().splitlines())
    candidates = [p for p in candidates if p.rstrip("/").split("/")[-1] not in excluded
                  and (not training_only or p.rstrip("/").split("/")[-1] in official_train)]
    candidates = candidates[::max(1, len(candidates) // (sessions * 3))]
    for prefix in candidates:
        masks = listing(prefix + "camera_chest/left/segmentation_masks/")["items"]
        masks = [m for m in masks if m["name"].endswith(".png")]
        if not masks:
            continue
        rgb = listing(prefix + "camera_chest/left/", "/")
        image_prefixes = [p for p in rgb["prefixes"] if any(s in p for s in ("/images/", "/rgb/", "/frames/", "/video_frames/"))]
        if not image_prefixes:
            continue
        images = listing(image_prefixes[0])["items"]
        by_stem = {Path(i["name"]).stem: i for i in images}
        pairs = [(m, by_stem[Path(m["name"]).stem]) for m in masks if Path(m["name"]).stem in by_stem]
        if not pairs:
            continue
        sampled = pairs[::max(1, len(pairs) // frames)][:frames]
        objects = [o for pair in sampled for o in pair]
        # Retain all small session metadata, including camera information/attributes.
        metadata = listing(prefix, "/")["items"]
        objects.extend(o for o in metadata if int(o["size"]) < 2_000_000)
        objects.extend(o for o in rgb["items"] if o["name"].endswith(".json"))
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(fetch, objects))
        selected.append({"session": prefix, "pairs": len(sampled), "objects": objects})
        save(DATA / "metadata" / metadata_name, {"sampling": "deterministic session spread; not yet representative", "sessions": selected})
        print(f"SANPO: {len(selected)} sessions, {sum(s['pairs'] for s in selected)} pairs", flush=True)
        if len(selected) >= sessions:
            break
    if not selected:
        raise RuntimeError("No annotated image/mask pairs found; inspect source layout")
    return {"sessions": len(selected), "pairs": sum(s["pairs"] for s in selected)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", choices=["brazil", "wet_potholes", "gutters", "rgbd_stairs", "sanpo", "rdd2022", "accessibility_barriers"])
    args = parser.parse_args()
    state = DATA / "status" / f"{args.dataset}.json"
    save(state, {"state": "running", "started": time.time()})
    try:
        if args.dataset == "sanpo":
            receipt = sanpo()
        elif args.dataset in ("rdd2022", "accessibility_barriers"):
            if args.dataset == "rdd2022":
                meta = get_json("https://api.figshare.com/v2/articles/21431547")
                file = next(f for f in meta["files"] if f["name"].endswith(".zip"))
                url, size, checksum = file["download_url"], file["size"], "md5:" + file["computed_md5"]
                path = DATA / "archives/RDD2022.zip"
            else:
                meta = get_json("https://zenodo.org/api/records/6382090")
                file = meta["files"][0]
                url, size, checksum = file["links"]["self"], file["size"], file["checksum"]
                path = DATA / "archives/accessibility_barriers.zip"
            save(DATA / "metadata" / f"{args.dataset}.json", meta)
            receipt = download(url, path, size, checksum)
            extract(path, DATA / "raw" / args.dataset)
            if args.dataset == "rdd2022":
                nested = sorted((DATA / "raw/rdd2022/RDD2022").glob("*.zip"))
                if len(nested) != 7:
                    raise ValueError("Expected seven country/camera archives in RDD2022")
                for archive in nested:
                    extract(archive, DATA / "raw/rdd2022/countries" / archive.stem)
                    print("Extracted RDD2022", archive.stem, flush=True)
        else:
            specs = {"brazil": ("t576ydh9v8", 4), "wet_potholes": ("tp95cdvgm8", 1),
                     "gutters": ("3psr2g4s7r", 1), "rgbd_stairs": ("6kffmjt7g2", 1)}
            receipt = mendeley(args.dataset, *specs[args.dataset])
        save(state, {"state": "complete", "finished": time.time(), "receipt": receipt})
        print(args.dataset, "complete", flush=True)
    except Exception as e:
        save(state, {"state": "failed", "error": str(e), "finished": time.time()})
        raise


if __name__ == "__main__":
    main()
