# USB camera support and accessibility validation

SightBridge has a dedicated USB Webcam source, selected by default. It enumerates
USB interfaces (including composite devices), detects UVC video-control interfaces,
requests Android CAMERA and per-device USB permission, and captures directly with
com.herohan:UVCAndroid:1.0.13. It does not require an external Camera2 provider.
The connected OnePlus 12 (CPH2573, Android 16) reports an openaicam webcam,
VID 0x32e6 / PID 0x9251, with UVC control and streaming interfaces.

Connect the webcam with an OTG adapter, select USB Webcam, and press Media Server Start or Start AI Processing.
Accept camera access, press the chosen start button again after the camera permission prompt, and allow
USB device access. Close competing USB camera applications if opening fails.
Unplugging the camera stops the HTTP output; reconnect and start to resume.
The camera negotiates a supported mode nearest the requested pixel count rather
than assuming every webcam supports 896 × 504. The preview surface is drained
through ImageReader to prevent the native producer's queue blocking. Frames feed
the existing bitmap-to-JPEG pipeline at a bounded target rate.

## Accessibility target

Target: WCAG 2.2 AA, interpreted for native Android, and Android accessibility
guidance. This is an implementation and validation record, not a certification.

- Camera sources use a vertical radio group with selected-state semantics and a
  single focus target per option; targets have a minimum height of 48dp.
- Processing and clipboard buttons have minimum 48dp targets, visible names, and native
  action/state semantics. The simplified dashboard has no stream privacy switch.
- Section titles expose heading semantics. Operational feedback and AI errors expose polite
  live regions. AI speech uses TalkBack when active, and app TTS otherwise.
- Content scrolls vertically, text uses scalable Material typography, source
  choices and registration controls wrap vertically. The app bar has a short title.
- Secondary explanatory text uses onSurfaceVariant instead of outline. Material
  surface/container colors are paired with their corresponding text colors.
- All actions are available through standard clicks; no drag-only actions or
  timed user responses are introduced by the UI. System permission dialogs are
  managed by Android.

Reference requirements:
https://www.w3.org/TR/WCAG22/
https://developer.android.com/develop/ui/compose/accessibility/api-defaults
https://developer.android.com/develop/ui/compose/accessibility/semantics

## Reproducible checks

Build, unit tests, and Android lint:

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest

Install both debug APKs, then run dashboard accessibility tests:

    adb shell am instrument -w -r -e class com.sightbridge.accessibility.DashboardAccessibilityTest com.sightbridge.test/com.sightbridge.accessibility.AccessibilityPreservingTestRunner

With a physically connected camera, run the opt-in hardware pipeline test:

    adb shell am instrument -w -r -e class com.sightbridge.camera.usb.UsbPipelineDeviceTest -e usbCamera true com.sightbridge.test/com.sightbridge.accessibility.AccessibilityPreservingTestRunner

The runner keeps enabled screen readers active while Android UI automation runs.
The hardware test accepts normal Android camera/USB dialogs and verifies a JPEG
start marker in the localhost MJPEG stream. It is skipped unless explicitly enabled.
USB enumeration unit tests cover composite cameras, noncamera devices, and a
streaming interface without video control. Dashboard tests check headings,
selection semantics, conditional URL visibility, and scroll access to the controls.

Manual checks still required before claiming full conformance: TalkBack spoken
order and announcements; Switch Access and hardware keyboard use; magnification;
200% text and small/landscape screens; focus visibility/obscuration; and a complete
contrast audit of every enabled, disabled, error, and selected state. Unit tests
that merely inspect constants do not establish accessibility conformance.

## Results on the connected OnePlus 12, 2026-10-10

- Debug build and Android lint completed successfully (lint reports existing
  dependency-version/custom-check warnings; no errors).
- 18 JVM tests passed, including three USB identification tests and the Material
  text contrast test (all tested text/background pairs meet 4.5:1).
- Both dashboard instrumentation tests passed at the original 135% font scale
  and again at 200%. The original 135% setting and enabled TalkBack service were
  verified after testing. Instrumentation semantics checks do not substitute for
  manual evaluation of TalkBack speech and gestures.
- The opt-in USB hardware pipeline test passed: the connected openaicam produced
  a JPEG frame delivered over localhost HTTP. Normal camera and USB permission
  dialogs were accepted through Android's accessibility automation.

## Device testing and pipeline scope

User-started processing holds a partial wake lock in a camera foreground service;
the display can turn off normally. Stopping both consumers releases that lock.
System lock credentials and screen timeout settings are unchanged.
ADB's `cmd power wakeup` can wake the handset before testing.
Required camera and USB dialogs are handled by the opt-in device test. Microphone,
storage, and Bluetooth permissions are not required for the UVC-only stream.

The media pipeline captures and JPEG-encodes camera frames, then serves MJPEG for
external consumers such as The vOICe. A separate AI consumer runs on-device object
detection and speaks recognized objects and their horizontal positions. AI and
media can run independently or together; stopping both releases the camera.
Scene narration, depth measurement, and safe-route determination are not implemented.
End-to-end behavior of an external consuming app remains a separate validation.

The current minimum platform is Android 16 (API 36). Historical test results
above are evidence for their stated builds, not a blanket conformance claim for
the latest build. See [release readiness](RELEASE_READINESS.md) for the current
validation record and criterion-based accessibility review still required.


## Returning Home with TalkBack

A two-finger swipe up inside SightBridge scrolls the dashboard. Android's Home
behavior depends on the phone's navigation mode:

- With three-button navigation, find the Home button and double-tap, or use
  TalkBack's default one-finger up-then-left gesture in one continuous motion.
- With gesture navigation, swipe up with two fingers from the very bottom edge
  to return Home. A swipe beginning inside the content still scrolls.

See [Google's TalkBack gesture instructions](https://support.google.com/accessibility/android/answer/6151827?hl=en).
SightBridge does not intercept these system gestures or exclude gesture regions.
The connected OnePlus was checked on 2026-10-12: TalkBack was enabled and
Android's three-button navigation overlay was active. This settings/code check
explains the reported behavior; it does not substitute for a physical gesture test.
