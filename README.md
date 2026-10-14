# SightBridge — offline camera streaming and object cues

**Latest testing release: [v1.2.0-testing.2](https://github.com/pranavlal/sightbridge/releases/tag/v1.2.0-testing.2) (Android version code 9). Minimum Android: 16 (API 36).**

SightBridge shares a USB webcam or phone-camera feed with The vOICe for Android
and optionally runs offline object recognition and position cues. A camera
foreground service keeps user-started processing alive when the dashboard closes.
Meta AI glasses can provide the camera feed through the Meta Wearables DAT SDK.
The included `0` credentials work in Meta AI Developer Mode. See [changes](CHANGELOG.md), [AI processing](docs/AI_PROCESSING.md),
and [release readiness](docs/RELEASE_READINESS.md).

This is a testing build. Production security review and a complete WCAG 2.2 AA
assessment using W3C WCAG2ICT guidance remain release gates.
See the [October 2026 security and accessibility review](docs/SECURITY_ACCESSIBILITY_REVIEW_2026-10-14.md)
for the current fixes, test results and remaining validation limits.

## Prerequisites & Environment

- **OS**: Arch Linux
- **Android SDK**: platform 36 and build tools 35.0.0; configure sdk.dir in local.properties
- **JDK**: OpenJDK 17 (`/usr/lib/jvm/java-17-openjdk`)
- **Gradle**: 8.13 via the checked-in wrapper; Android Gradle Plugin 8.13.2

---

## 1. Meta AI App & Glasses Configuration

DAT 1.0.1 downloads from Maven Central through the normal Gradle build. It does
not require a GitHub token. Install the Meta AI app and pair supported Meta AI
glasses with the phone.

### Enable Developer Mode in Meta AI App
1. On your iOS or Android phone, open the **Meta AI app**.
2. Go to **Settings > App Info**.
3. Tap the **App version** number **5 times** until the Developer Mode toggle appears.
4. Enable **Developer Mode**.

For local testing, the app uses `0` for both DAT credentials. For distribution
outside Developer Mode, register SightBridge in the Wearables Developer Center
and pass its credentials to Gradle with `-PmwdatApplicationId=...` and
`-PmwdatClientToken=...` (or set those properties in a private Gradle
properties file). Register the app's `sightbridge` callback scheme there.

On first launch, SightBridge asks for Android camera, Bluetooth and notification
permissions. Select **Meta Glasses**, then **Connect Meta Glasses** to complete
registration in Meta AI. Starting the media server or AI for the first time will
request the separate Meta glasses camera permission through Meta AI. If a
permission is declined, the start action can be tried again after granting it.
The glasses feed supports the same MJPEG server and on-device AI controls as
the other camera sources. The phone camera permission is only needed by the
phone, mock and USB camera foreground service paths; Meta glasses streaming
uses the connected-device foreground service type.

---

## 2. Build & Verification Commands

### Install Gradle Wrapper
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk gradle wrapper
```

### Build Debug APK
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug
```

The tester APK is `app/build/outputs/apk/debug/sightbridge.apk`.
For a smaller OnePlus build, add `-PsightbridgeAbi=arm64-v8a`.
It requires Android 16 or later and is signed with a debug key; use a separately
managed signing key for production. APKs are build outputs and are not committed.

For a signed release-style testing APK, run
`python3 scripts/build_signed_testing.py`. Its verified output is
`app/build/outputs/apk/qa/sightbridge.apk`, using a separate **SightBridge
Testing** identity and a persistent private key outside the repository.
See [signed testing instructions](docs/SIGNED_TESTING.md) for installation,
secure key backup and future updates.

### Run Verification
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

For device tests with an attached USB webcam and TalkBack enabled, install both
the debug app and test APK, then run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -r -e usbCamera true com.sightbridge.test/com.sightbridge.accessibility.AccessibilityPreservingTestRunner
```

The test runner preserves enabled accessibility services; the Android test
automation default otherwise temporarily suppresses TalkBack. The USB webcam
and TalkBack checks passed on the connected OnePlus during the October 2026
testing cycle. The Meta glasses video path still needs physical-glasses testing.

### Install onto Connected Android Device / Emulator
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew installDebug
```

---

## 3. Testing Without Hardware (Mock Device Kit)

If you don't have physical Ray-Ban Meta glasses connected:
1. The SDK includes `mwdat-mockdevice` (`com.meta.wearable:mwdat-mockdevice`).
2. The standalone **Mock Generator** option tests SightBridge's downstream
   camera pipeline. Meta's Mock Device Kit is a debug dependency for testing
   DAT registration, sessions and permissions; physical glasses are needed to
   verify the real video path.

## Privacy and Network Safety

- MJPEG always binds to `127.0.0.1`, so only apps on the phone can receive camera frames. There is no LAN-sharing mode.
- The localhost feed intentionally allows other apps on the same phone, including The vOICe for Android, to read frames while the media server runs. It has no per-app authentication. This interoperability choice is accepted by the project owner; access by other local apps is not a release blocker. The server remains localhost-only in the dashboard and stops serving frames when media output stops.
- The Meta DAT SDK's optional analytics and SDK crash reporting are disabled in the manifest. Meta AI registration and device access still use Meta's companion app and its service.
- The streaming URL is `http://localhost:8080/mjpeg`. The previous `/live.mjpeg` path remains supported for existing consumers; malformed and oversized requests are rejected and concurrent clients are capped.

## Launcher Icon

The SightBridge icon depicts two amber eyes beneath a cyan bridge, with gold sound-wave arcs above it. It represents bridging visual perception into sound. The high-resolution source is stored in `artwork/sightbridge-icon-source.png`; Android density and adaptive-icon resources are under `app/src/main/res`.

USB webcam support and accessibility verification are documented in [USB camera and accessibility](docs/USB_CAMERA_AND_ACCESSIBILITY.md).

## Offline AI and independent streaming

- **Copy Streaming URL** appears after the server starts and copies the same-handset URL for The vOICe for Android.
- **Media Server Start / Media Server Stop** control MJPEG output.
- **Start AI Processing / Stop AI Processing** control on-device object detection and speech independently of the server.

Both features share the selected camera. Stopping one leaves the other running;
stopping both releases the camera. AI results list recognized objects and their
left/center/right and upper/lower positions. Independent detections can announce
an unrecognized object or unidentified visual region when names are unreliable.
These cues do not establish distance or a safe route, and not every object is detected.
See [AI processing](docs/AI_PROCESSING.md) for the model and validation details.

When TalkBack or another spoken-feedback service is enabled, SightBridge sends
AI descriptions to that service as accessibility announcements and keeps its
own voice quiet. Otherwise it uses the phone's selected default text-to-speech
engine and voice. That voice must be downloaded and work without a network
connection; if it is unavailable, the descriptions remain visible and the app
shows a speech warning. SightBridge does not turn TalkBack off.


## Ground hazard dataset research

Public dataset acquisition and the controlled detection comparison are documented
in [ground hazard research](docs/GROUND_HAZARD_RESEARCH.md) and the
[accuracy report](reports/ground-hazard-accuracy-2026-10-12/REPORT.md).
Combining Accessibility Barriers with the other sources improved stairs, but the
shared-class macro F1 gain was only 0.71 percentage points, below the predefined
five-point criterion. No hazard detector was added to the Android app.
Reproduction scripts are in [the training workspace](training/ground_hazards/README.md);
datasets, environments and model checkpoints are excluded from Git and APKs.
