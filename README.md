# SightBridge — offline camera streaming and object cues

**Current testing version: 1.2.0 (Android version code 3). Minimum Android: 16 (API 36).**

SightBridge shares a USB webcam or phone-camera feed with The vOICe for Android
and optionally runs offline object recognition and position cues. A camera
foreground service keeps user-started processing alive when the dashboard closes.
The Meta Wearables DAT integration is experimental and currently uses developer
credentials. See [changes](CHANGELOG.md), [AI processing](docs/AI_PROCESSING.md),
and [release readiness](docs/RELEASE_READINESS.md).

This is a testing build. Production security review and a complete WCAG 2.2 AA
assessment using W3C WCAG2ICT guidance remain release gates.

## Prerequisites & Environment

- **OS**: Arch Linux
- **Android SDK**: platform 36 and build tools 35.0.0; configure sdk.dir in local.properties
- **JDK**: OpenJDK 17 (`/usr/lib/jvm/java-17-openjdk`)
- **Gradle**: 8.13 via the checked-in wrapper; Android Gradle Plugin 8.13.2

---

## 1. Configure GitHub Personal Access Token

The Meta DAT SDK is hosted on GitHub Packages (`maven.pkg.github.com/facebook/meta-wearables-dat-android`). To download the SDK dependencies:

1. Create a GitHub Personal Access Token (classic) with `read:packages` scope at [GitHub Developer Settings](https://github.com/settings/tokens).
2. Set the token using one of the following methods:

### Option A: Environment Variable (Recommended for CLI)
```bash
export GITHUB_TOKEN=ghp_your_token_here
```

### Option B: Local Properties File
Add your token to `local.properties`:
```properties
sdk.dir=/opt/android-sdk
github_token=ghp_your_token_here
```

---

## 2. Meta AI App & Glasses Configuration

### Enable Developer Mode in Meta AI App
1. On your iOS or Android phone, open the **Meta AI app**.
2. Go to **Settings > App Info**.
3. Tap the **App version** number **5 times** until the Developer Mode toggle appears.
4. Enable **Developer Mode**.

> **Note for Developer Mode:** In Developer Mode, `APPLICATION_ID` and `CLIENT_TOKEN` in `AndroidManifest.xml` are set to `"0"` for local testing.

---

## 3. Build & Verification Commands

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

### Run Verification
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

### Install onto Connected Android Device / Emulator
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew installDebug
```

---

## 4. Testing Without Hardware (Mock Device Kit)

If you don't have physical Ray-Ban Meta glasses connected:
1. The SDK includes `mwdat-mockdevice` (`com.meta.wearable:mwdat-mockdevice`).
2. You can test streaming, session creation, and device capabilities using simulated feeds from your phone/emulator camera.

## Privacy and Network Safety

- MJPEG binds to `127.0.0.1` by default so only apps on the phone can receive camera frames.
- The dashboard always starts localhost-only streaming; it provides no LAN-sharing control. The adapter still supports token-protected LAN HTTP for internal tests, which must be removed or hardened before a public release.
- The localhost feed intentionally allows other apps on the same phone, including The vOICe for Android, to read frames while the media server runs. It has no per-app authentication. This interoperability choice is accepted by the project owner; access by other local apps is not a release blocker. The server remains localhost-only in the dashboard and stops serving frames when media output stops.
- The only supported HTTP path is `/live.mjpeg`; malformed and oversized requests are rejected and concurrent clients are capped.

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
