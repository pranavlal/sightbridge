# SightBridge 1.2.0 — Testing pre-release 2

Signed Android testing build for Android 16 or later on arm64-v8a devices.
This updates the earlier `com.sightbridge.testing` installation: Android version
code 9, app version `1.2.0-testing`. It uses the same testing signing key.

## Changes

- Update the Meta Wearables DAT integration to SDK 1.0.1 and add the glasses
  video decoder. Physical Meta glasses were unavailable for end-to-end testing.
- Preserve TalkBack during Android instrumentation. When a spoken accessibility
  service is active, AI descriptions go through accessibility announcements;
  otherwise the app uses the phone's selected downloaded offline TTS voice.
- Remove the unused LAN camera-sharing mode. MJPEG now always binds to the
  phone's loopback address for same-phone apps such as The vOICe.
- Improve camera-frame ownership and stream cleanup. Add the USB webcam and
  accessibility device checks and further ground-hazard research records.

## Verification

The signed APK passed signature and static 16 KB alignment checks. It updated
the existing testing app on the connected OnePlus, reached the dashboard, and
delivered USB webcam JPEG frames through the local stream. The debug build
passed 51 JVM tests, Android lint with zero errors, and 11 active phone tests
with TalkBack enabled; one optional device test was skipped by design.

APK SHA-256: `d4e728bf4f1dc0702f2890fe0d7fd593ded27322c856d0c8710cac86b9c1157d`

This is a testing release. The Meta glasses video path, long-duration hardware
testing, and complete manual security and accessibility assessments remain open.
See [signed testing instructions](https://github.com/pranavlal/sightbridge/blob/v1.2.0-testing.2/docs/SIGNED_TESTING.md)
and the [security and accessibility review](https://github.com/pranavlal/sightbridge/blob/v1.2.0-testing.2/docs/SECURITY_ACCESSIBILITY_REVIEW_2026-10-14.md).
