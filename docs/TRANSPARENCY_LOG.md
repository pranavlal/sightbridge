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

### Entry 6: Second Codex CLI Re-Audit & Pipeline Decoupling
- **Re-Audit Findings Executed**:
  - **Decoupled Async Pipeline**: Updated `HttpMjpegVoiceStreamAdapter` to launch a background consumer loop on `Dispatchers.Default` that continuously polls `LatestFrameBuffer`, encodes JPEG, and transmits to `MjpegHttpServer`. `submitFrame()` is strictly non-blocking.
  - **Heartbeat & Automatic Recovery**: Added `recordHeartbeat()` in `HealthWatchdog` registered on frame arrival, enabling automatic state recovery from `DEGRADED` to `HEALTHY`.
  - **CameraX Buffer Safety**: Wrapped `CameraXPhoneSource` frame creation in `try { ... } finally { imageProxy.close() }` to guarantee Android CameraX buffer release.
  - **Pipeline Startup Validation**: Updated `MainActivity.kt` to check `Result.isSuccess` on all startup steps (`initialise()`, `connect()`, `start()`, `startStreaming()`) and report typed errors if any step fails.

### Entry 7: Application Naming & APK Output Customization
- **Requirement**: Set exact application display name to `sightBridge` and configure the debug APK filename output to `sightBridge-debug.apk`.
- **Actions**:
  - Created `app/src/main/res/values/strings.xml` with `<string name="app_name">sightBridge</string>`.
  - Updated `AndroidManifest.xml` application and activity labels to `@string/app_name`.
  - Updated `app/build.gradle.kts` variant output configuration to generate `sightBridge-debug.apk`.
  - Successfully built `app/build/outputs/apk/debug/sightBridge-debug.apk` (71.2 MB).

### Entry 8: Proactive Bug Fix Execution
- **Bug Hunt Fixes Executed**:
  - **HTTP Socket Tracking**: Added active tracking of client sockets in `CopyOnWriteArrayList<Socket>()` in `MjpegHttpServer.kt`. Set socket read timeouts (`socket.soTimeout = 5000`) and closed all active client sockets deterministically upon `stop()`.
  - **GC & Allocation Optimization**: Replaced per-frame `Paint` object allocations in `SimulatedCameraSource.kt` with persistent class fields.
  - **Session Error Validation**: Updated `MetaSdkAdapter.kt` to check session creation and return `Result.failure()` on error.
  - **Collector Scope Isolation**: Updated `LaunchedEffect` in `MainActivity.kt` to use structured coroutines bound to the effect lifecycle, ensuring source switching cancels previous frame/state collectors.
  - **State Agreement**: Disabled Localhost toggle switch in `MainActivity.kt` while streaming is active to prevent UI/server configuration disagreement.
  - **Independent Component Cleanup**: Wrapped each component release in `MainActivity.onDestroy()` in its own `runCatching` block to guarantee full cleanup even if one component throws an exception.

### Entry 9: Final Release-Readiness Remediation
- **Release Fixes Executed**:
  - **Application Startup Guard**: Wrapped `Wearables.initialize(this)` in `runCatching` in `SightBridgeApp.kt` to prevent startup crashes on non-Meta hardware.
  - **Persistent MJPEG Client Keep-Alive**: Removed socket read timeout in `MjpegHttpServer.kt` client keep-alive loop so HTTP MJPEG streams remain open indefinitely.
  - **Atomic Capacity Enforcement**: Synchronously registered accepted client sockets in `MjpegHttpServer.kt` to enforce `maxClients = 10` capacity.
  - **Permission Launcher Thread Safety**: Wrapped `permissionLauncher.launch()` in `withContext(Dispatchers.Main)` in `MainActivity.kt`.
  - **Source Switching Unwind**: Implemented automatic `stopStreaming()` and `disconnect()` on previously active camera sources when switching sources in `MainActivity.kt`.

### Entry 10: Automated End-to-End Pipeline Integration Test
- **Requirement**: Automatically test camera stream capture, buffer decoupling, HTTP MJPEG server broadcast, and health watchdog monitoring.
- **Actions**:
  - Implemented `EndToEndPipelineTest.kt` in `app/src/test/java/com/sightbridge/integration/EndToEndPipelineTest.kt`.
  - Tested synthetic camera frame generation, non-blocking frame buffer submission, socket HTTP GET `/live.mjpeg` client connection, HTTP 200 OK header, `multipart/x-mixed-replace` boundary validation, JPEG frame byte payload extraction, and `HealthWatchdog` `HEALTHY` status assertion.
  - Handled JVM mock Bitmap fallback in `FrameProcessor.kt` and lazy `Paint` initialization in `SimulatedCameraSource.kt`.
  - Executed `./gradlew test` with **100% clean pass** (**BUILD SUCCESSFUL in 2s**).
