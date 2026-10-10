# Changelog

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
