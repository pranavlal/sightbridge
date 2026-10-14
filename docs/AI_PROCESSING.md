# On-device AI processing

The AI and MJPEG media server are separate consumers of one selected camera.
PipelineController serializes start/stop/source changes and owns the camera frame
collector. AI samples receive a private bitmap copy before the original is passed
to the media server. With AI alone the originals are recycled after sampling;
with media enabled the existing media frame buffer owns the originals. The camera
stops only when neither consumer remains active. Camera disconnects stop both.

## Background operation

PipelineService owns the sources, controller, speech, and health monitoring. The
dashboard binds to it and never releases an active pipeline when it closes or is
recreated. Phone CameraX capture follows the service lifecycle rather than the
activity lifecycle. Start and stop commands also run in the service's scope.

Starting either consumer while the dashboard is visible promotes the service to
a camera foreground service, with a low-importance ongoing notification and a
Stop all action. A partial wake lock keeps frame processing active with the screen
off. Stopping the last consumer or failing startup removes the notification,
releases the wake lock, and stops the started service; if the dashboard is still
bound, the idle service remains available for the next start. Process termination
does not automatically restart camera capture. Users must start processing again.
Camera permission is required before foreground promotion. Notification permission
is requested on Android 13+; denying it does not prevent processing.

Android reference: https://developer.android.com/develop/background-work/services/fgs/service-types#camera

## Model and speech

MediaPipe Tasks Vision 0.10.35 runs Google's EfficientDet-Lite2 int8 model on CPU
at 448×448 input resolution. Lite0 remains bundled for comparison. These are
closed-vocabulary models with 80 COCO categories, including chair, toilet, couch,
and dining table, but no dedicated cupboard, wooden barrier, or screen-door class.
The models are bundled in assets; inference requires no image upload or API key.

Model source:
https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/1/efficientdet_lite2.tflite

SHA-256:
b3f50554cb0ea559e90328845f7d9ba4d13c8bff372914d24e06bc8bb72fa896

Lite0 comparison model SHA-256:
0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb

Official model/API guide:
https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector/android

Scene thumbnails are checked at most four times per second. Inference runs on the
first frame, meaningful scene changes, or a five-second periodic refresh, with at
most one inference per second and one outstanding inference. The gate compares
64×48 grayscale thumbnails against the last successfully analyzed scene, not the
previous video frame, so gradual changes accumulate. Overall differences and
8×8 tile differences detect broad and localized changes. Small uniform exposure
shifts are compensated; major brightness changes trigger fresh analysis. Each
scene/refresh can request at most two additional confirmation analyses at the
same one-second minimum interval, including when the scene is stationary.

The gate resets when AI starts. Rotation or input geometry changes trigger new
analysis. Scene checking maintains AI health heartbeats while inference
is intentionally idle. Camera frames continue flowing to the media server.

These thresholds are initial defaults, not guarantees: low-contrast, very small,
or color-only changes may be missed until the periodic refresh. They can be tuned
using representative webcam footage.
The classifier returns up to 15 candidates above score 0.5. Speaking a name
requires score ≥0.75 and two consecutive spatially matching observations. Toilet
requires ≥0.9 and three observations because chairs were reported as toilets.
Scores are not calibrated probabilities. Label changes, missing observations,
input geometry changes, or a gap over eight seconds drop previous confirmations.
These are initial precision-oriented defaults; repeated confident errors remain
possible. They need testing on the user's actual furniture and barriers.

## Independent position cues

ML Kit object detection 17.0.2 runs in single-image mode with multiple-object
detection and classification disabled, returning up to five unnamed object
regions. Unknown cues are not fabricated from low classifier scores.
Reference: https://developers.google.com/ml-kit/vision/object-detection/android

A supplemental U2Netp foreground model locates prominent regions when the
object detectors miss them. This is salient-region segmentation, not a guarantee
of a physical object or obstacle. It uses ONNX Runtime Android 1.23.2 with two CPU
threads. Input is resized to 320×320 and normalized with the reference ImageNet
means/deviations and the image's maximum RGB value. Absolute mask scores are kept;
min/max mask normalization is deliberately avoided to prevent amplifying noise.
Connected foreground components require score ≥0.7, at least 0.5% of mask pixels,
30% bounding-box occupancy, and ≥20% pixels scoring ≥0.85. Full-frame components
are discarded. The thresholds describe model outputs, not confidence in safety.

Model source (pretrained U2Netp, Apache-2.0 upstream):
https://github.com/xuebinqin/U-2-Net
ONNX artifact and reference preprocessing:
https://github.com/danielgatis/rembg/blob/main/rembg/sessions/u2netp.py
https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx
SHA-256: 309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8
The artifact MD5 also matches the reference implementation's checksum.
Upstream license is retained in U2NET_LICENSE.txt and the application's assets.

All position cues require two spatially matching observations. Boxes overlapping
reliably named objects and duplicate region proposals are suppressed. Up to three
descriptions are spoken, reserving a slot for an unfamiliar region when one exists.
Horizontal and vertical thirds refer to the upright image, matching the vOICe
view; no distances are inferred. Examples: "chair on the left", "Unrecognized
object in the lower right", or "Unidentified region on the left" for segmentation.
The segmentation wording deliberately avoids claiming a recognized object.
Detector errors preserve named recognition and show a position-cue warning.

## Speech

When TalkBack or another spoken-feedback accessibility service is enabled, AI
descriptions are sent as accessibility announcements for the screen reader to
speak. AI startup does not initialize its own TTS engine in this mode or change
accessibility settings. The speech route is checked for each announcement, so
toggling TalkBack during AI processing switches the output route. Without a
screen reader, Android TextToSpeech uses the phone's selected default engine and
voice only if that voice is downloaded and does not require a network connection.
Otherwise, choose an offline default voice in Android's text-to-speech settings.
Speech occurs at most every six seconds; unchanged descriptions repeat no more
than every fifteen seconds. Stop AI Processing cancels work and stops the app's
own speech; it does not interrupt or stop TalkBack. Accessibility announcements
already handed to the screen reader remain under that service's control.
If speech is unavailable, results remain visible and the UI explains how to
select a downloaded text-to-speech voice. Inference errors are shown rather than replaced
with simulated detections.

AI recognizes the model's supported object classes; it does not infer depth,
describe arbitrary scenes, identify every obstacle, or declare a route safe.
An empty result says “No reliable detections in this frame.” While confirmation
is pending, "Checking objects" is displayed and not spoken. Neither message means
the image is empty.

## Clipboard and independent controls

Copy Streaming URL copies `http://localhost:8080/mjpeg` for an Android consumer on the
same handset. The dashboard always uses localhost binding.
Copying does not change the shared status live region. On Android 13 and newer,
the system provides the copy confirmation; older versions receive an explicit
"URL copied" accessibility announcement. Android clipboard access alerts from
other apps are independent of SightBridge's announcements.
Media Server Start/Stop and Start/Stop AI Processing are distinct, persistently
visible buttons with enabled states. Camera source changes are disabled while
either consumer runs. The initial dashboard contains camera selection and the
four processing buttons. The streaming URL and copy button appear only after a
successful media start and disappear after media stops or fails. AI results are
shown only while AI runs; diagnostics and LAN settings are absent from the
dashboard. Results and controls remain vertically scrollable
with 48dp minimum targets and headings for screen-reader navigation.

## Validation

JVM tests cover conservative naming, spatial/temporal confirmation, invalid and
low-score observations, mixed known/unknown scenes, segmentation-mask filtering,
description positions, duplicate suppression,
empty-result wording, camera identification, server behavior, and contrast.
Device tests cover conditional URL visibility, clipboard contents, accessible controls,
foreground-service capture with the dashboard destroyed, activity recreation,
independent AI/media operation, notification stop handling,
real webcam JPEG delivery, AI inference count, screen-reader speech routing,
AI continuing after media stops, and MJPEG continuing after
AI stops. A client disconnect during frame writing is handled as an ordinary
I/O closure rather than an uncaught coroutine exception.

The offlineCheck build type removes INTERNET permission and uses a separate
application ID, so local inference can be proven without disabling the phone's
Wi-Fi. Build it and its test runner with
`-PsightbridgeTestBuildType=offlineCheck`; optional
`-PsightbridgeAbi=arm64-v8a` limits verification artifacts to the handset's ABI.
OfflineModelEvaluationTest compares both recognition models and independent
position detectors. Its optional reference image is read from app-private
`files/ai-reference.jpg`; private photos are not bundled in the repository or
uploaded. Pass instrumentation arguments `assertOffline=true` and, for a suitable
reference frame, `expectRegion=true`.

Run the device suite with the attached UVC camera:

    adb shell am instrument -w -r -e usbCamera true com.sightbridge.test/com.sightbridge.accessibility.AccessibilityPreservingTestRunner

## Deployment record, 2026-10-10

The corrected debug APK installed successfully on the connected OnePlus 12.
The final build, Android lint, and all 21 JVM tests passed. The first device run
passed the three dashboard tests and webcam JPEG test, and reached successful AI
inference and completed speech output in the independent-consumer test. That test
then exposed an uncaught media-client broken-pipe exception. The corrected APK
handles that exception, fixes copied URLs in LAN mode, and explicitly selects an
offline speech voice.

Wireless ADB reconnected at 192.168.88.36:42577. The updated test APK installed
successfully and all five instrumentation tests passed on the corrected app:
clipboard copying in both binding modes, headings and accessible controls, webcam
JPEG delivery, and independent AI/media operation including offline speech
utterance completion. The client-disconnect regression did not recur.

All three dashboard instrumentation tests also passed at 200% font scaling.
The original 135% font scaling and 60-second screen timeout were restored after
testing, and SightBridge was reopened. The debug foreground keep-screen-on flag
remains active while the app is open. These checks supplement, rather than replace,
a complete manual WCAG/TalkBack conformance audit.

## Scene-gating validation

All 29 JVM tests and build/lint checks passed, including eight scene-gate tests.
On the OnePlus 12, the actual-model test verified unchanged-frame skipping,
source-bitmap ownership, inference after a brightness/scene change, and a periodic
refresh. The three dashboard tests and UVC JPEG test also passed. The combined
suite encountered a Compose SnapshotStateObserver layout-thread error while
clicking a media control in the independent-consumer test; that test passed when
rerun independently. The scene-gating build is installed on the handset.

## Offline recognition validation, 2026-10-11

All 46 JVM tests passed. The separate offlineCheck APK, which has no INTERNET
permission, passed its actual-model comparison on the connected OnePlus 12.
The private USB-camera reference frame yielded no reliable COCO names or ML Kit
object boxes. U2Netp supplied a confirmed visual cue, spoken as "Unidentified
region on the left", without assigning a cupboard label. A uniform gray frame
produced no visual regions. Warm mean times over four runs were 86.25 ms for
Lite0, 156.75 ms for Lite2, 29 ms for ML Kit, and 981 ms for U2Netp.
These measurements demonstrate offline execution and the fallback on one view;
they do not establish recognition accuracy across household scenes.

The final debug APK is installed. Android lint passed, and the combined
dashboard, background-service, static-scene and attached-USB suite passed
(nine active checks; the deployment-only check is opt-in). The initial combined
run exposed a test-control readiness race; waiting for the command's busy state
to clear resolved it. An independent USB run also passed.
The deployment-only check then passed with both consumers active, no localization
warning, and one TalkBack announcement (zero app-TTS utterances). That live view
produced an unrecognized object in the lower center and an unidentified region
in the upper left; total analysis time was 1345 ms. TalkBack remained enabled.
Instrumentation ends its target process, so normal app controls were used to
restore media and AI after the tests; the camera foreground service was verified.
