# Changelog

## Unreleased

- Update Meta Wearables DAT to 1.0.1 from Maven Central and migrate to the
  session, camera and stream lifecycle.
- Ask for Android permissions on first launch, then request Meta AI registration
  and glasses camera access when the glasses source is used.
- Decode the glasses' HEVC camera frames into the existing AI and MJPEG pipeline,
  and run glasses capture as a connected-device foreground service.
- Release rejected and undelivered camera bitmaps, wait for stream workers to
  stop before clearing their buffers, and make the MJPEG bind mode immutable.
- Prevent duplicate permission prompts and repeat Meta registration, and opt out
  of the DAT SDK's optional analytics and crash reporting.
- Keep TalkBack enabled during instrumentation tests and verify speech uses
  accessibility announcements when a spoken-feedback service is active.
- Use the phone's selected default TTS voice only when it is downloaded and
  works offline; show a warning if offline speech is unavailable.
- Remove the unused plain-HTTP LAN streaming mode and bind MJPEG to loopback
  for every caller.

## 1.2.0 — 2026-10-11 (testing; production release pending)

- Fix signed-build dashboard startup by providing the lifecycle owner explicitly, and preserve MediaPipe's Flogger stack inspection, native protobuf access and ONNX JNI classes through R8. Add an external ADB startup check for the actual signed APK; Android version code is now 7.

- Add a release-style signed SightBridge Testing build with a separate app ID, persistent private testing key, signing helper, signature/alignment verification and secure key-custody instructions.

- Record the owner's acceptance of unauthenticated same-phone streaming for apps such as The vOICe; remove local-app authentication from release blockers while retaining localhost-only dashboard streaming.

- Require Android 16 (API 36), matching the connected OnePlus; compile and target API 36.
- Update to Android Gradle Plugin 8.13.2, its supported Gradle 8.13 toolchain, and Kotlin compilerOptions; remove the standalone R8 override.
- Keep camera capture in a camera foreground service with a visible notification and Stop all action; release the camera and wake lock when processing stops.
- Retain independent media and AI controls across dashboard recreation and background operation.
- Simplify the initial screen to camera choices and processing controls; show the streaming URL and copy button only while the server runs.
- Route AI announcements through TalkBack when a spoken accessibility service runs; use offline app TTS otherwise.
- Avoid a duplicate app-generated clipboard announcement on Android 13 and later. Android/OEM clipboard announcements remain system-controlled.
- Upgrade recognition to bundled EfficientDet-Lite2 and confirm names across frames, with stricter toilet thresholds.
- Add independent ML Kit object locations and bundled U2Netp visual-region cues for scenes without reliable names; include the U2Net license.
- Skip unchanged scenes while retaining periodic refresh and bounded confirmation checks.
- Add a separate no-INTERNET verification build and actual-model/device regression checks. Camera reference images are not committed or uploaded.
- Use lifecycle-aware Compose state collection and exclude the Meta mock-device SDK from release dependencies.
- Update CameraX to 1.6.2 to remove the old 4 KB-aligned native image library; add a renewable two-minute wake-lock timeout.
- Remove SDK-contributed microphone, all-files/shared-storage and legacy Bluetooth permissions from the merged app manifest; forbid outgoing cleartext traffic and add installed-package permission checks.
- Name debug APKs sightbridge.apk; increment the Android version code to 3.
- Document production release gates and the WCAG 2.2 AA evaluation plan; add a repeatable native-library alignment check.

The preceding AI/service implementation passed 46 JVM tests, Android lint, the
combined device suite and a no-network model check. The Android 16 baseline and
build-toolchain changes are subject to the validation record in
[release readiness](docs/RELEASE_READINESS.md). No WCAG conformance or security
certification is claimed.

## 1.1.0 — 2026-10-10

- Add direct UVC webcam capture, camera discovery, USB permissions, supported-mode selection, and disconnect handling.
- Add offline object detection with spoken object names and left/center/right positions.
- Give AI processing and the MJPEG media server independent start/stop controls sharing one camera.
- Add streaming URL copying for The vOICe, including access tokens in LAN mode.
- Improve screen-reader semantics, touch targets, contrast, headings, and large-text layouts.
- Keep debug builds awake while the app is in the foreground.
- Handle media-client disconnects without crashing and strengthen stream validation and resource cleanup.
- Include launcher artwork and Android resource/packaging updates.

Validated with 21 JVM tests, Android lint, five device tests on the OnePlus 12 with an attached openaicam UVC webcam, and three accessibility tests at 200% font scaling. AI results do not determine distance or a safe route; a full manual accessibility conformance audit remains separate.
