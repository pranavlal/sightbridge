# SightBridge Development & Transparency Log

This document records all interactions, prompts, architectural decisions, and build phase executions for full transparency on how the SightBridge project is designed and built.

---

## Log History

### Entry 1: Initial DAT Toolkit Prompt & Environment Setup
- **Prompt**: Checked Meta Wearables DAT getting-started toolkit AI prompt.
- **Environment**: Arch Linux (`7.0.11-arch1-1`), OpenJDK 17 (`/usr/lib/jvm/java-17-openjdk`), Android SDK (`/opt/android-sdk`), Gradle 9.6.1.
- **Actions**:
  - Initialized Gradle project structure for `/home/pranav/sightbridge`.
  - Configured `settings.gradle.kts` with Meta GitHub Packages Maven repository (`https://maven.pkg.github.com/facebook/meta-wearables-dat-android`).
  - Added `gradle.properties` (`android.useAndroidX=true`).
  - Added `libs.versions.toml` with `mwdat-core`, `mwdat-camera`, `mwdat-display`, and `mwdat-mockdevice`.

### Entry 2: HTTP MJPEG Streamer & Localhost Security
- **Requirement**: Stream Meta glasses camera feed via HTTP MJPEG (`multipart/x-mixed-replace`) on port 8080 for consumption by external applications (e.g. The vOICe).
- **Security Requirement**: Provide binding control toggle:
  - Localhost Only (`127.0.0.1:8080`): Prevents exposing video feed when on public/cellular networks.
  - All Network Interfaces (`0.0.0.0:8080`): Exposes feed over Wi-Fi when explicitly enabled.
- **Actions**:
  - Implemented `MjpegHttpServer.kt` with dynamic IP binding.
  - Implemented `FrameProcessor.kt` for JPEG compression.
  - Updated `MainActivity.kt` with compose controls and endpoint URL displays.

### Entry 3: Software Requirements & Architecture Specification
- **Specification Received**: 38-section Software Requirements and Architecture Specification for Mobile Blind-Navigation Assistance Using Meta AI Glasses.
- **Repository Setup**: Initialized Git repository and pushed to `https://github.com/pranavlal/sightbridge`.

### Entry 4: Phase 1 Infrastructure & Modular Implementation
- **Implemented Packages**:
  - `com.sightbridge.core.model`: Implemented `CameraFrame`, `CameraSourceState`, `CameraCapabilities`, `PixelFormat`, `CameraSourceType`.
  - `com.sightbridge.core.error`: Implemented `NavigationError` sealed hierarchy (`CameraError`, `MetaSdkError`, `VoiceStreamError`, `MemoryError`).
  - `com.sightbridge.core.health`: Implemented `ComponentHealth` and `HealthWatchdog` for real-time component health tracking per Section 27.
  - `com.sightbridge.camera.api`: Implemented `CameraSource` contract per Section 7.
  - `com.sightbridge.camera.meta`: Implemented `MetaSdkAdapter` encapsulating developer-preview Meta DAT SDK behind `CameraSource` interface per Section 8.
  - `com.sightbridge.camera.phone`: Implemented `CameraXPhoneSource` for CameraX phone fallback per Section 9.
  - `com.sightbridge.camera.mock`: Implemented `SimulatedCameraSource` for hardware-free synthetic test pattern frame generation.
  - `com.sightbridge.frame.buffer`: Implemented `LatestFrameBuffer` non-blocking frame buffer with stale frame dropping (`maxStaleAgeMs = 500`) per Section 6.2 & 10.3.
  - `com.sightbridge.output.voicestream`: Implemented `VoiceStreamAdapter` interface & `HttpMjpegVoiceStreamAdapter` implementation per Section 20.
  - `com.sightbridge.app`: Updated `MainActivity.kt` Compose dashboard for multi-source camera selection, health watchdog indicators, and vOICe stream endpoints.

### Entry 5: Codex CLI Codebase Audit & Remediation
- **Audit Findings Executed**:
  - **Thread Safety**: Added `ReentrantLock` synchronization to `LatestFrameBuffer` to prevent atomic offer/poll race conditions.
  - **Monotonic Freshness**: Enforced non-negative age validation and future timestamp rejection in `LatestFrameBuffer.poll()`.
  - **HTTP Server**: Refactored `MjpegHttpServer` to use structured Kotlin coroutines (`Dispatchers.IO`) and `NetworkInterface` enumeration for IPv4 address discovery.
  - **Active Watchdog**: Added active background watchdog loop to `HealthWatchdog` to automatically detect frame freezes (> 2.0s without frames) and mark components `DEGRADED`.
  - **CameraX Binding**: Updated `CameraXPhoneSource` with full `ProcessCameraProvider` and `ImageAnalysis` lifecycle binding.
  - **Accessibility**: Updated `MainActivity.kt` with scrollable layout and TalkBack live-region announcements (`LiveRegionMode.Polite`).
  - **Testing**: Added `HealthWatchdogTest` covering active staleness detection. Executed `./gradlew test` with 100% clean pass (**BUILD SUCCESSFUL in 4s**).
