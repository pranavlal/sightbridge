# Does Accessibility Barriers improve detection?

Measured 2026-10-12. Adding Accessibility Barriers improved staircase detection relative to training on the other sources, but did not meet the predeclared meaningful overall improvement criterion. It slightly reduced performance on the non-accessibility holdout. These results concern this frozen-backbone, compute-matched experiment; they do not establish the ceiling of these datasets or a drop-off detector.

## Main comparison

Three seeds per training arm, nine model fits, and 3,418 fixed test images. Macro detection F1 over potholes, stairs, and curbs increased from **22.76% to 23.47%**, a **+0.71 percentage-point** difference. The paired exploratory 95% interval was **+0.14 to +1.36 points**.

Before training, meaningful overall gain was defined as at least five absolute percentage points of macro F1 with the paired interval above zero. The measured gain is small and does not meet that criterion. F1 combines detection precision and recall; these numbers are not image-classification accuracy.

| Class | Other-data F1 | Combined F1 | Other-data AP50 | Combined AP50 |
|---|---:|---:|---:|---:|
| pothole | 18.41% | 17.28% | 11.60% | 11.42% |
| stairs | 49.35% | 52.16% | 39.88% | 46.56% |
| curb | 0.52% | 0.97% | 0.47% | 0.70% |

AP50 measures ranking/localization at box intersection-over-union 0.5. AP75 uses the stricter 0.75 overlap. Thresholds for F1 were selected on validation data, then frozen for testing. Results are means across three seeds.

## Accuracy on held-out Accessibility Barriers images

The held-out accessibility set contains 1,853 images, with 468 stairs, 351 curbs, and 711 single-step annotations. Models trained only on Accessibility Barriers perform better on this source than the combined models. Mixing sources under the same update budget dilutes this source's training exposure and introduces different annotation/viewpoint conventions.

| Class | Other data only F1 | Accessibility only F1 | Combined F1 |
|---|---:|---:|---:|
| stairs | 3.50% | 24.32% | 14.01% |
| curb | 0.07% | 1.31% | 0.59% |
| single_step | Not trained | 6.44% | 4.28% |

A single-step class exists only in the accessibility training data; adding it is additional class coverage, not a like-for-like accuracy increase. Its measured F1 remains low.

## Transfer to other sources

Excluding all accessibility test images, the shared-class macro F1 difference was **-0.98 points** (exploratory 95% interval **-1.68 to -0.04**). The staircase benefit therefore does not demonstrate a general improvement on the other domains.

Pothole generalization is poor, particularly on the held-out Indian/US RDD images. Curbs and single steps are poorly localized. High gutter scores are uninformative: only two unique gutter images remained in testing after deduplication, and duplicate source images have inconsistent boxes. Gutter performance was excluded from the primary gain calculation.

## Controlled experiment

- Baseline: Brazilian masks converted to connected-component pothole boxes, a bounded country-balanced RDD sample, wet/dry pothole boxes, gutter boxes, RGB-D stair-surface extent boxes, and SANPO curb/stair component boxes.
- Accessibility-only arm: visible stair boxes, typed curb boxes, and other single-step boxes from CVAT annotations.
- Combined arm: the baseline sources plus accessibility examples, sampled with 40% accessibility and 60% other sources; other sources were balanced by source.
- Same COCO-pretrained SSDLite320 MobileNetV3 backbone, cached float16 features with float32 inference/training heads, three paired initializations (17, 29, 43), 1,000 updates, batch size 12, optimizer, and learning-rate schedule.
- Backbone and normalization statistics frozen; classification and box-regression heads trained. Identical initial head tensors were verified for each paired seed.
- Five checkpoints per run. Checkpoint selection used validation mean AP50 over the arm's trained classes (including the small gutter validation set for baseline/combined); thresholds used validation F1 only. No checkpoint or threshold was chosen using test results.
- Unannotated classes were excluded from source-specific classification loss and evaluation. Their absence was not treated as a negative label.
- Same 3,418 test images in all arms. Test predictions were saved and their metrics recomputed independently; manifest/protocol hashes were checked.

## Split and uncertainty limits

The prepared pool contains 13,774 images; 103 records were removed for exact duplication or conflict with a higher-priority holdout. Conservative perceptual links and exact SHA-256 matches were checked before training. This is not exhaustive near-duplicate detection.

Whole Brazilian highways, RDD countries, SANPO sessions, RGB-D scene families/stereo views, and accessibility annotation-task batches were held together. Wet/gutter duplicates were clustered. Accessibility task batches are annotation batches, not verified physical locations; unknown scene leakage may remain. RGB-D boxes derived from step-surface masks and accessibility boxes of visible vertical increments differ in extent. This annotation mismatch is a plausible contributor to poor transfer.

The paired bootstrap resampled three training seeds and collection/image groups, stratified by source, 2,000 times. There were only two held-out accessibility task groups, two Brazilian highway groups, two RDD countries, one RGB-D scene family, and one SANPO session; these intervals are conditional exploratory estimates, not population-wide walking-route confidence. The earlier Brazilian exploratory test was already used in a prior study and is not a pristine deployment acceptance set.

## Decision

Retain Accessibility Barriers for stair-focused training and as a source of additional step labels. Do not treat simple pooling as a demonstrated meaningful overall improvement. The next experiment should use source-aware sampling/heads, harmonized box conventions, larger inputs for narrow curb/step edges, and full-model fine-tuning. Reserve new independently collected walking routes for acceptance testing.

No model or alerts were added to SightBridge. Generic stairs and curbs do not label descending direction, measured drop depth, or warning time. This study does not test walking safety or Android latency.

## Reproduction and artifacts

From the repository root, use the project virtual environment:

```sh
training/ground_hazards/.venv/bin/python training/ground_hazards/study_manifest.py
training/ground_hazards/.venv/bin/python training/ground_hazards/run_detection_study.py
training/ground_hazards/.venv/bin/python training/ground_hazards/analyze_detection_study.py
training/ground_hazards/.venv/bin/python training/ground_hazards/write_study_report.py
training/ground_hazards/.venv/bin/python -m unittest discover -s training/ground_hazards -p 'test_*.py'
```

Eight tests passed, including one-to-one detection matching, class correctness, AP behavior, and zero classification gradient for unannotated classes, plus archive-extraction checks. Python syntax and whitespace checks passed. Completed fits are reused only when manifest/protocol hashes match.

[Machine-readable comparison](comparison.json) · [Class/source metrics CSV](class_metrics.csv) · [Predeclared protocol](protocol.json)

Raw predictions, feature caches, initializations, histories, thresholds, checkpoints, and dataset audit live under the ignored `data/ground_hazards/study/` directory. Original pretrained weights came from the [official Torchvision SSDLite model](https://docs.pytorch.org/vision/stable/models/generated/torchvision.models.detection.ssdlite320_mobilenet_v3_large.html). Dataset sources and attribution records are in `training/ground_hazards/sources.json` and retained source metadata.
