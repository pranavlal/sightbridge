# SightBridge - Meta Wearables Device Access Toolkit (DAT) Integration

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

### Install onto Connected Android Device / Emulator
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew installDebug
```

---

## 4. Testing Without Hardware (Mock Device Kit)

If you don't have physical Ray-Ban Meta glasses connected:
1. The SDK includes `mwdat-mockdevice` (`com.meta.wearable:mwdat-mockdevice`).
2. You can test streaming, session creation, and device capabilities using simulated feeds from your phone/emulator camera.
