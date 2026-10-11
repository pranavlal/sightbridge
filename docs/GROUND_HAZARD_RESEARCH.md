# Ground-hazard data and training start — 2026-10-11

Update, 2026-10-12: Accessibility Barriers completed its download, checksum
verification, and extraction. A nine-fit controlled detection comparison is
complete; see [the accuracy report](../reports/ground-hazard-accuracy-2026-10-12/REPORT.md).
The acquisition and baseline sections below preserve the initial setup record.

The research workspace is [training/ground_hazards](../training/ground_hazards/README.md).
Android inference and bundled models have not changed.

## Acquisition

Downloaded, integrity-checked, and extracted:

- Brazilian cracks/potholes: 2,235 source images and their binary masks; 2,234
  unique images after removing one exact duplicate. 564 contain pothole pixels.
- RDD2022: official 13,264,172,619-byte archive verified against its published
  MD5; all seven country/camera archives extracted, containing 47,420 images,
  including 9,665 from India. Published test images have no public box labels.
  Country annotations include additional classes beyond the four benchmark
  targets; they need an explicit mapping. One box has the unexpected label
  `D0w0`, and its image is quarantined pending review.
- Wet/dry potholes: 713 images and XML/YOLO annotations, plus two dashcam videos.
  One XML labels a box `o`; that image is listed in the quarantine manifest and
  excluded from future training until reviewed.
- Uncovered gutters: 83 gutter images with valid normalized boxes, plus the
  separate 150-image bollard set. The advertised gutter count was 85.
- RGB-D stairs: color/depth images, line labels, and step-surface annotations.
  There are 2,276 training, 556 validation, and 154 test color images.
- SANPO pilot: 384 matched RGB/panoptic-mask frames from eight real chest-camera
  sessions. Its official split places seven selected sessions in training and
  one in testing. Verified red-channel semantic encoding against the official
  loader. Curb pixels occur in 265 frames; stair pixels occur in only seven.

Accessibility Barriers is still downloading through the persistent user service
`sightbridge-data-accessibility-barriers.service`. Its official archive is
7,972,407,510 bytes and has a published MD5. The worker resumes partial downloads,
checks integrity, and extracts after completion. Live status is recorded in
`data/ground_hazards/status/accessibility_barriers.json`.

ExoNet is deferred at the user's request because IEEE DataPort requires login.
The PEDESTRIAN paper's stated Zenodo DOI does not resolve, and the API returns
404; no images from that source were downloaded. Sources and license evidence
are recorded in `training/ground_hazards/sources.json` and local source metadata.

## Initial training evidence

Two CPU experiments ran using actual Brazilian image/mask pairs. Whole
state/highway groups were kept together: 1,876 training images, 95 validation
images, and 263 exploratory test images. No exact duplicate or road group
crosses these splits. The validation split has 60 pothole-positive images and
the test split 41. These counts differ substantially from the training class
balance and need to be considered when comparing results.

The initial three-epoch, 160-pixel baseline predicted only background and had
zero foreground IoU. A revised eight-epoch encoder-decoder with skip connections
and foreground-frequency weighting achieved only 0.00270 validation foreground
IoU (0.27%); its best checkpoint predicted almost the entire image as pothole.
Neither experiment provides useful detection. The revised experiment did not
evaluate the test split. The earlier exploratory test split was already evaluated
and must not be presented as pristine final acceptance data.

The purpose of these experiments was to exercise the real training, checkpoint,
and evaluation workflow and expose class-imbalance failure. They do not validate
drop-off recognition. No model was exported or installed in SightBridge.
The NVIDIA driver was unavailable; CPU PyTorch runs in a project environment.

## Remaining work

Finish the accessibility download; extend the dataset audit to its annotations.
The SANPO pilot is insufficient for stair diversity and does not label descent
direction. Add targeted descending-edge examples and representative footage
from the actual walking camera mount before claiming representative coverage.
Keep scene classes, bounding boxes, masks, and depth as distinct supervision.

The next model experiment should use transfer learning and larger hazard-focused
inputs/crops, with validation against simple foreground baselines. Deployment
requires unseen local routes and evaluation of missed encounters, false alerts
per walking minute, warning time, Android inference, and alert timing.

Three archive-extraction checks passed (path traversal rejection, symbolic-link
rejection, and valid extraction). Python syntax checks and `git diff --check`
also passed. Detailed dataset audit, quarantine list, and training metrics are
stored under the ignored local data/run directories.
