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

### Entry 11: TalkBack & Screen Magnifier Accessibility Audit
- **Requirement**: Verify accessibility compliance for blind users (TalkBack screen reader) and low-vision users (Android Screen Magnifier).
- **Actions**:
  - Validated `LiveRegionMode.Polite` status announcements in `MainActivity.kt` so TalkBack automatically speaks pipeline state changes out loud.
  - Validated Material 3 `FilterChip` and `Button` accessibility roles, selection states, and 48dp touch targets.
  - Implemented `AccessibilitySemanticsTest.kt` unit test suite covering TalkBack live region modes, status announcement formatting, and magnifier scrollable layout bounds.
  - Executed `./gradlew test` with **100% clean pass**.

### Entry 12: Information Security & Data Protection Hardening
- **Requirement**: Perform information security review and execute security hardening fixes.
- **Actions**:
  - Disabled cloud backup in `AndroidManifest.xml` (`android:allowBackup="false"`) to prevent local video stream metadata from leaking to cloud backups.
  - Restricted `FileProvider` cache path in `file_paths.xml` to a dedicated `shared_photos/` subdirectory.
  - Restricted `sightbridge:` deep link intent filter data host to `android:host="auth"` in `AndroidManifest.xml`.
  - Pinned Gradle distribution SHA256 checksum (`distributionSha256Sum`) in `gradle-wrapper.properties`.
  - Verified no hardcoded tokens or API secrets exist in source code or Git history.

### Entry 13: Offline recognition, foreground processing and release review (2026-10-11)

- Added a camera foreground service with independent media/AI consumers, a Stop all notification, and a renewable bounded wake lock. Activity recreation/background operation retain processing; idle processing releases resources.
- Simplified the dashboard to camera radios and processing controls; URL/copy appear only while the server runs. AI speech uses TalkBack when active and app TTS otherwise. App-generated duplicate clipboard speech is removed on the supported platform.
- Bundled EfficientDet-Lite2 and U2Netp, added independent ML Kit object locations, and required spatial/temporal confirmation of labels and unnamed cues. Kept visual regions distinct from identified objects and added scene gating/periodic refresh.
- Verified model execution in a separate no-INTERNET APK using a private local camera reference. Images were not uploaded or committed. Documented model sources, hashes, license and recognition limitations.
- Matched the minimum Android version to the connected OnePlus: API 36 (Android 16). Updated the supported AGP/Gradle combination and CameraX; moved UI state collection to lifecycle-aware Compose APIs and excluded Meta mock-device SDKs from release dependencies.
- Found and removed SDK-contributed microphone, all-files/shared-storage and legacy Bluetooth permissions. Checked the merged release manifest and added installed-package regression checks. Outgoing cleartext is disabled; this does not authenticate the raw localhost camera feed.
- Confirmed debug and shrunk release builds, 46 JVM tests, lint with zero errors, ten active combined device checks, and five dashboard checks at 200% text scaling. Restored the original 135% setting.
- Added a static 16 KB native-library checker. CameraX 1.3.2 failed; CameraX 1.6.2 passed across 83 arm64 libraries and independent ZIP alignment checks. The handset uses 4 KB pages, so 16 KB runtime execution remains a release gate.
- Documented production signing, SDK/component review, feed authentication, dependency/license review, long-duration reliability and WCAG 2.2 AA manual assessment in RELEASE_READINESS.md. No production or accessibility conformance certification is claimed. The current bounded token/private-key scan does not establish a complete secret-history audit.

### Entry 14: Accepted same-phone stream sharing (2026-10-11)

- The owner confirmed that access by other apps on the phone, including The vOICe for Android, is intended. Updated the README and release review to record this acceptance and remove per-app authentication as a release requirement. Localhost binding, shutdown behavior and the separate LAN review remain in scope. This is a documentation change; runtime behavior is unchanged.

### Entry 15: Signed testing artifact (2026-10-11)

- Created a separate release-style qa build with application ID com.sightbridge.testing and label SightBridge Testing, preserving side-by-side installation with the debug app.
- Generated a persistent RSA-3072 PKCS12 testing key outside the repository, protected by a random password and owner-only directory/file permissions. Keystore and password are excluded from Git; neither secret was printed or uploaded.
- Added a signing helper that reuses the key, reads passwords from a private file instead of shell arguments, verifies signature and 16 KB alignment, and writes an APK checksum. Documented secure backup and future update continuity in SIGNED_TESTING.md.
- Verified the signed APK's v3 signature, identity, Android 16/arm64 baseline, non-debuggable flag, sensitive-permission exclusions and all 83 native-library alignments. Release vital lint passed. Full signed-build device validation remains pending; signing does not remove Android sideloading warnings or complete production approval.

### Entry 16: Signed-build hardware validation and speech recovery (2026-10-11)

- Removed the old debug and instrumentation packages and installed the signed testing app using normal OnePlus installer confirmations through ADB. Kept device security settings intact.
- Actual signed-build execution exposed Compose lifecycle-owner reflection, MediaPipe/Flogger caller inspection, native protobuf field access and ONNX JNI lookup failures concealed by debug builds. Added an explicit lifecycle owner and required R8 keep rules; raised version code to 7 with the same persistent signing key.
- Added an external ADB dashboard startup check. Verified the final signed dashboard and concurrent USB AI/media processing manually through ADB; the stream returned 65 JPEG frames in three seconds and AI descriptions updated. Release vital lint, signature verification and 83 native-library alignment checks passed. The 46 JVM tests passed after the lifecycle change.
- The user reported all TalkBack speech sounding unfamiliar. Read settings showed eSpeak NG, the default male variant and English system locale; logs also contained earlier eSpeak native crashes, without establishing the cause of the speech change. Explicitly selected English (United Kingdom) through the phone's TTS settings, preserving engine, rate, pitch and variant. The user confirmed speech returned to normal. TalkBack stayed enabled.
- These checks do not establish full WCAG conformance, long-duration reliability, production signing or 16 KB runtime compatibility.
