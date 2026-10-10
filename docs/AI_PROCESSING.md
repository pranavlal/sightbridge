# On-device AI processing

The AI and MJPEG media server are separate consumers of one selected camera.
PipelineController serializes start/stop/source changes and owns the camera frame
collector. AI samples receive a private bitmap copy before the original is passed
to the media server. With AI alone the originals are recycled after sampling;
with media enabled the existing media frame buffer owns the originals. The camera
stops only when neither consumer remains active. Camera disconnects stop both.

## Model and speech

MediaPipe Tasks Vision 0.10.35 runs Google's EfficientDet-Lite0 int8 model on CPU.
The model is bundled in assets, so inference requires no download or API key.

Model source:
https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite

SHA-256:
0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb

Official model/API guide:
https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector/android

Frames are sampled at most once per second, with only one outstanding inference.
Results below 50% confidence are filtered. Up to three descriptions are spoken,
including left/center/right positions computed from detection bounding boxes in
the correctly rotated frame. Identical descriptions are deduplicated. English
Android TextToSpeech uses an installed voice that reports no network requirement.
Speech occurs at most every six seconds; unchanged descriptions repeat no more
than every fifteen seconds. Stop AI Processing cancels work and stops speech.
If speech is unavailable, results remain visible and the UI explains how to
install/enable an English voice. Inference errors are shown rather than replaced
with simulated detections.

AI recognizes the model's supported object classes; it does not infer depth,
describe arbitrary scenes, identify every obstacle, or declare a route safe.
An empty result says “No recognized objects in this frame.”

## Clipboard and independent controls

Copy Streaming URL copies the localhost endpoint for an Android consumer on the
same handset. LAN mode also includes the session access token in that URL.
Media Server Start/Stop and Start/Stop AI Processing are distinct, persistently
visible buttons with enabled states. Camera source changes are disabled while
either consumer runs. Settings, results, and controls remain vertically scrollable
with 48dp minimum targets and headings for screen-reader navigation.

## Validation

JVM tests cover description positions, confidence filtering, deduplication,
empty-result wording, camera identification, server behavior, and contrast.
Device tests cover clipboard contents in both binding modes, accessible controls,
real webcam JPEG delivery, AI inference count, offline speech initialization and
utterance completion, AI continuing after media stops, and MJPEG continuing after
AI stops. A client disconnect during frame writing is handled as an ordinary
I/O closure rather than an uncaught coroutine exception.

Run the device suite with the attached UVC camera:

    adb shell am instrument -w -r -e usbCamera true com.sightbridge.test/androidx.test.runner.AndroidJUnitRunner

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
