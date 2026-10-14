# Additional free data search, 2026-10-12

Acquisition and quantitative source tests are recorded in
[the additional-source results](../reports/ground-hazard-additional-sources-2026-10-12/REPORT.md).
These are controlled research tests, not a deployment acceptance assessment.
The goal is additional descending-edge supervision and difficult ordinary-ground
examples, rather than simply more generic staircase pictures.

## Candidates

| Source | What it adds | Access and limitations |
|---|---|---|
| [SS4Blind curb and terrain datasets](https://github.com/elnino9ykl/SS4Blind) | Listed 100-image curb segmentation set, 120 RGB-D terrain images and 400 RGB-D-SS images | Public Google Drive links are listed. Dataset reuse terms and original-author provenance need verification before ingestion; a public download link is not a license. Small but more targeted to pedestrian curb perception than road-damage data. |
| [GO Stanford / GONet](https://cvgl.stanford.edu/gonet/dataset/) | Stereo fisheye traversability examples, including hand-labelled traversable/non-traversable sets | CC BY-NC-SA 3.0: research candidate with noncommercial and share-alike restrictions. Classification supervision is not a descending-edge box label. Robot camera viewpoint differs from the wearable camera. |
| [Global Navigation Dataset](https://robotixx.github.io/GND/) | Ten campuses, outdoor RGB/360 imagery, LiDAR and stairs/walkway traversability maps | Public institutional repository linked by authors. Verify repository license and download sizes first. Labels are projected global maps, not ready-made image masks; extracting image targets requires calibration and projection. |
| [Head-camera staircase-approaching study](https://pmc.ncbi.nlm.nih.gov/articles/PMC10256100/) | Paper describes 12,187 annotated staircase stills and 375 approach videos with ascent/descent/avoidance | A promising viewpoint match. A verified usable public download and dataset license were not established during this search. Paper access does not grant dataset access. |
| [SANPO](https://github.com/google-research-datasets/sanpo_dataset) | More walking-camera sessions, contextual ground masks and scene variation | Already acquired only as an eight-session pilot. A targeted expansion of this CC BY 4.0 source is preferable to an indiscriminate full multi-terabyte download. Generic stair/curb semantics still need descending-edge labels. |

These are candidates for improved task coverage, not a ranking based on measured
accuracy. Retain physical scenes/sequences together when assigning splits.

## Wikimedia Commons pilot acquired

Run:

```sh
training/ground_hazards/.venv/bin/python training/ground_hazards/acquire_commons.py
```

The bounded downloader queries six topics, accepts JPEG files with explicit
CC BY, CC BY-SA, CC0 or public-domain metadata, caps individual downloads at
10 MiB, validates image decoding, and retains original URLs, authors/credits,
license URLs, the complete license metadata and SHA-256 checksums.
Individual [Commons reuse conditions](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia)
apply; preserve attribution and share-alike obligations where relevant.

Downloaded 21 images to ignored `data/ground_hazards/raw/commons_review/`.
Contact-sheet visual screening retained 12 candidates and excluded nine search
mismatches or extreme stairwell overview images. The retained pool includes
stair descent views, Indian roadside trench scenes, a curb/sidewalk scene and
pavement shadows. These descriptions are screening notes, not training labels.

- `manifest.json`: acquisition metadata for all 21 images.
- `reviewed_manifest.json`: visual screening notes; twelve candidates remain unlabelled.
- `receipt.json`: acquisition completion and failures (zero in this run).
- No exact duplicates within the batch or against the existing 13,774-image
  study manifest. Near-duplicate and wider location grouping checks remain.
- Two photos of the same construction drain share one scene group.
- No images have been merged into the labelled training set or the fixed test set.

Before training, inspect full-resolution photos, reject unsuitable/ambiguous
views, annotate visible descending edges and hazard extents, record exhaustive
class coverage, and keep ordinary-ground examples separate from assumed negatives.
Perform near-duplicate checks against every old split. Preserve the earlier
fixed evaluation set and collect a new independent walking-route holdout.

The Android app and its models are unchanged by this acquisition. A new controlled
comparison is required to establish whether these additions improve detection.


## Acquisition and tests completed

- GO Stanford's three small labelled archives: 4,800 stereo images, tested with a
  separate traversability classifier using the published splits.
- SS4Blind's three targeted archives: 620 matched image/mask pairs downloaded;
  619 retained for external detector evaluation after duplicate screening.
  Archive reuse terms were not found, so these images were not mixed into training.
- SANPO: 336 additional annotated pairs from seven previously unused official
  training sessions, tested in a paired three-seed detector augmentation study.
- GND: repository license verified as CC0; one processed UMD segment and its
  matching map downloaded and checked. This subset has maps and point clouds,
  rather than camera photographs, so it was excluded from image-model tests.

See the results report for exact metrics, integrity receipts, split limitations,
and reproduction commands. The Commons photographs remain unlabelled; no model
or hazard alert was added to the Android app.


Measured outcomes: the SANPO expansion changed shared-class macro F1 from
23.47% to 22.99% on the unchanged 3,418-image holdout. On the external
RGB-D-SS curb set, F1 improved from 3.19% to 9.44%, but remains weak.
GONet's separate unsafe/traversable classifier reached 98.23% F1 on its own
indoor fisheye test set; that is not drop-off localisation accuracy.
