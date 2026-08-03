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

### Entry 3: System Architecture Specification & Phase 1 Assignment
- **Specification Received**: 38-section Software Requirements and Architecture Specification for Mobile Blind-Navigation Assistance Using Meta AI Glasses.
- **Phase 1 Objectives**:
  - Modular Android structure (`core/`, `camera/`, `frame/`, `perception/`, `navigation/`, `output/`, `diagnostics/`).
  - Encapsulate `CameraSource` interface (`CameraFrame`, `CameraSourceState`, `CameraCapabilities`).
  - Implement Phone Camera Source (CameraX) and Simulated/Mock Camera Source.
  - Implement `LatestFrameBuffer` (unbounded queue prevention & frame dropping).
  - Implement `VoiceStreamAdapter` interface & HTTP client component.
  - Implement Component Health Monitoring framework.
  - Unit tests for pipelines, buffers, and state machines.
