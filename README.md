# SightBridge - Meta Wearables Device Access Toolkit (DAT) Integration

**Current app version: 1.1.0 (Android version code 2).**

This project integrates the **Meta Wearables Device Access Toolkit (DAT) SDK** (MWDAT) on Arch Linux.

## Prerequisites & Environment

- **OS**: Arch Linux
- **Android SDK Path**: `/opt/android-sdk`
- **JDK**: OpenJDK 17 (`/usr/lib/jvm/java-17-openjdk`)
- **Gradle**: 9.6.1

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
- Publishing on `0.0.0.0` exposes an unencrypted camera feed to the current local network. The app generates a session token and includes it in the displayed LAN URL, but the transport is still plain HTTP: use it only on a trusted network and stop the pipeline immediately after testing.
- The only supported HTTP path is `/live.mjpeg`; malformed and oversized requests are rejected and concurrent clients are capped.

## Launcher Icon

The SightBridge icon depicts two amber eyes beneath a cyan bridge, with gold sound-wave arcs above it. It represents bridging visual perception into sound. The high-resolution source is stored in `artwork/sightbridge-icon-source.png`; Android density and adaptive-icon resources are under `app/src/main/res`.

USB webcam support and accessibility verification are documented in [USB camera and accessibility](docs/USB_CAMERA_AND_ACCESSIBILITY.md).

## Offline AI and independent streaming

- **Copy Streaming URL** copies the same-handset URL for pasting into The vOICe for Android. It includes the required session token when LAN sharing is enabled.
- **Media Server Start / Media Server Stop** control MJPEG output.
- **Start AI Processing / Stop AI Processing** control on-device object detection and speech independently of the server.

Both features share the selected camera. Stopping one leaves the other running;
stopping both releases the camera. AI results list recognized objects and their
left/center/right positions. They do not establish distance or a safe route.
See [AI processing](docs/AI_PROCESSING.md) for the model and validation details.
