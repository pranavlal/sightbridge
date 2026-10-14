# Ground hazard research workspace

The controlled accuracy comparison is complete. See
[the measured comparison report](../../reports/ground-hazard-accuracy-2026-10-12/REPORT.md).
Three training arms with three seeds each were tested against the same held-out
images. Accessibility data improved stairs relative to the other-data baseline,
but the overall macro F1 gain was only 0.71 points and did not meet the
predeclared five-point meaningful-gain criterion. Non-accessibility transfer
decreased by 0.98 points. No detector was deployed.

This is a training and data-audit workspace, separate from Android inference.
No hazard model is enabled in the app. Ground-hazard recognition, descending
direction, metric depth, and a safe path are different targets.

## Acquisition

Official sources and licenses are recorded in `sources.json`; retain the authors'
metadata and attribution when redistributing data or derived artifacts. Large
assets live in ignored `data/ground_hazards/`, not Git or APK assets.

Run `python3 training/ground_hazards/acquire.py DATASET`, where DATASET is
`brazil`, `wet_potholes`, `gutters`, `rgbd_stairs`, `sanpo`, `rdd2022`, or
`accessibility_barriers`. Needs Python requests and curl. Downloads resume from
`.part` files, produce SHA-256 receipts, check published MD5 where available,
validate ZIP CRCs, and check extraction paths and available space. An interrupted
extraction is retried before the completion marker is written.

SANPO is a bounded pilot: eight annotated real chest-camera sessions, at most
48 RGB/mask pairs each, spread deterministically across session IDs. This is
**not yet a representative split**. The full release is approximately 6 TB.
Masks use SANPO's panoptic encoding, verified against its official loader:
the red channel contains semantic class IDs, and green × 256 + blue encodes
instance IDs. The audit reports class coverage per session. Never treat mask
instance IDs as semantic class IDs. Retain official session splits before training.
Real depth includes estimates rather than universally accurate ground truth.

ExoNet is deferred because login is required, as requested by the user.
The PEDESTRIAN paper's Zenodo DOI currently fails to resolve; it is not downloaded.

## Audit and baseline

    python3 training/ground_hazards/prepare.py
    training/ground_hazards/.venv/bin/python training/ground_hazards/train_baseline.py --epochs 3

The audit writes `prepared/audit.json` and a JSONL manifest for Brazilian pothole
masks. It validates binary values and image/mask dimensions, removes exact image
duplicates (rejecting conflicting annotations), and assigns whole state/highway
groups to train, validation, and test. These groups are inferred from filenames;
they do not prove separation of every capture session. Near duplicates still
need investigation. Source releases contain additional annotation types that
must be used by separate training heads or workflows. Boxes are not mask labels.

The small CPU baseline is binary pothole segmentation trained from scratch on
Brazil only at 160×160. It is an exploratory reference, not the proposed final
model. Validation selects a checkpoint; use `--evaluate-test` explicitly to
evaluate the held-out exploratory test split after model selection. The initial
three-epoch run produced no foreground detections, including on its test split;
that split is therefore no longer a pristine final acceptance set. Subsequent
development uses validation only. It reports foreground pixel IoU/precision/recall, including
zero-detection failures, rather than misleading background pixel accuracy.
Resizing may erase very small hazards. Checkpoints and metrics are under `runs/`.
The run records seed, library version, configuration, and manifest hash.

## Work required before integration

1. Inspect source-specific label conventions and harmonize narrow curb/step boxes
   with stair-surface annotations. All seven public acquisition jobs are complete.
2. Quarantine the unexpected `o` label in the wet/dry set; inspect its image/XML
   rather than silently treating it as pothole or background.
3. Inspect representative samples and near duplicates across datasets. Preserve
   source splits; group stereo views and all frames from one sequence together.
4. Inspect SANPO stairs/curb coverage and decode its annotation provenance.
   Accessibility-barrier annotations omit some top-down stair/step views; they
   cannot provide exhaustive negative examples for downward edges.
5. Build training targets for potholes, descending steps, curb drops, and open
   drains. Generic stairs and curbs do not supply descent or severity labels.
6. Add local footage from the actual mount with ordinary ground, repairs, shadows,
   wet surfaces, poor lighting, occlusion, and camera motion. Reserve unseen
   routes and distinct hazard encounters for final evaluation.
7. Train a compact model with the available supervision; evaluate missed hazard
   encounters, false alerts per walking minute, and warning time. Validate
   exported inference, device latency, sustained load, and speech scheduling
   before adding alerts to SightBridge.

The NVIDIA driver was unavailable at initial setup. CPU PyTorch was installed in
a project virtual environment; this does not change system Python packages.

## Acquisition completion

RDD2022 and Accessibility Barriers completed successfully. Their transient user
services exited and may no longer appear in `systemctl status`; completion and
integrity receipts remain under `data/ground_hazards/status/` and `archives/`.
Their retained logs can be inspected with:

    journalctl --user -u sightbridge-data-rdd2022.service -u sightbridge-data-accessibility-barriers.service

The acquisition commands can resume interrupted partial files. RDD2022's 13 GB
archive and Accessibility Barriers' 8 GB archive matched published MD5 hashes.
Accessibility Barriers contains 5,538 matched image/annotation entries: 3,564
step boxes, 1,492 stair boxes, 143 ramp boxes, and 922 grab-bar boxes.

## Additional free-image review pool

See [the additional-source review](../../docs/GROUND_HAZARD_ADDITIONAL_SOURCES.md).
`acquire_commons.py` downloads a bounded openly licensed photo batch with provenance.
The initial acquisition contains 21 images; visual screening retained 12 unlabelled
candidates. This pool is excluded from training and evaluation until annotated
and checked for scene overlap and near duplicates.


## Additional-source acquisition and tests

[Additional-source results](../../reports/ground-hazard-additional-sources-2026-10-12/REPORT.md)
cover GONet traversability classification, external SS4Blind detector evaluation,
and a three-seed SANPO expansion comparison. `acquire_additional.py` downloads
bounded subsets with receipts; `audit_additional.py` decodes masks and quarantines
overlaps. `evaluate_gonet_source.py` tests scene classification separately from
`run_additional_study.py`'s detection comparison. `write_additional_report.py`
publishes the measurements without dataset images or model weights.
The original SANPO pilot receipt controls baseline reconstruction even after
additional sessions have been downloaded.
