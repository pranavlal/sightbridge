# Release readiness — 2026-10-11

Status: suitable for controlled testing; production approval is pending.
This review covers SightBridge 1.2.0, including the USB/phone dashboard, background
camera service, local MJPEG feed, offline recognition, clipboard copying and speech.
It is not a penetration test or an accessibility conformance declaration.

## Supported platform and build

The connected OnePlus CPH2573 reports Android 16, API 36. The app now requires
API 36 and compiles/targets API 36. Older Android versions cannot install this
build. Build with JDK 17, AGP 8.13.2 and its documented Gradle 8.13 combination.
Dependency versions remain pinned. The Gradle distribution is checksum-verified.
The debug APK is `app/build/outputs/apk/debug/sightbridge.apk`.
Version code 3 distinguishes this build from 1.1.0.

This is a supported Android 16 toolchain, not a claim that every library is the
newest available. Before release, review Kotlin, Compose, Activity, Lifecycle,
CameraX, the USB library, AI engines and Meta SDK updates together. Upgrade major
toolchain versions with their migration guides and rerun actual-device checks.
The SDK version alone does not establish modern architecture or accessibility.
The UI now collects service state with lifecycle-aware Compose APIs; capture
belongs to the service rather than the activity. The legacy APK naming API still
needs migration when moving to AGP 9. Background speech currently uses the
accessibility announcement event, deprecated in API 36: retain the tested
TalkBack route until a replacement is verified with the dashboard closed.
Foreground UI updates should use semantics/live regions rather than ad hoc
announcements. Do not switch background output to competing app TTS as a shortcut.

## Changes and verified behavior

See [CHANGELOG](../CHANGELOG.md) and [AI implementation and device measurements](AI_PROCESSING.md).
The preceding implementation passed 46 JVM tests, Android lint, nine active
dashboard/background/USB checks, a deployment smoke check and an actual-model
check in a separate APK without INTERNET permission. The latter proved inference
on a private reference frame and rejection of a blank foreground scene. It does
not prove that all third-party SDK behavior in the normal app is offline.

TalkBack remained enabled. AI announcements went through accessibility with zero
app-TTS utterances in the live smoke check. The restored normal app delivered
64 complete JPEG frames in a three-second sample. These are functional checks,
not recognition-accuracy or accessibility certification.

The Android 16 baseline/toolchain update requires fresh validation; final results
are recorded below after the build review. Earlier results remain historical.

## Security and privacy release gates

| Area | Current evidence | Required before production |
| --- | --- | --- |
| Feed exposure | Dashboard binds to 127.0.0.1. No LAN control is shown. Local feed deliberately permits access by other apps on the same phone, including The vOICe. The project owner accepted this behavior on 2026-10-11. | Local-app access and lack of per-app authentication are accepted design choices, not release blockers. Verify localhost-only binding, interoperability and prompt shutdown when media stops. |
| LAN adapter | Internal adapter supports token-protected plain HTTP LAN output. | Remove unused LAN capability from production or implement authenticated secure transport and an explicit sharing flow. Do not describe a token over HTTP as encrypted. |
| HTTP robustness | Header limit 8192 bytes, initial read timeout five seconds, ten-client cap, conflated frame queues, disconnect handling. | Test slow readers, repeated malformed connections, port contention, connection saturation, repeated start/stop and resource release in a signed release build. |
| Components | Pipeline service and FileProvider are non-exported; provider shares only shared_photos cache paths; PendingIntents are immutable. Launcher/USB/auth activity is exported. | Inspect the merged release manifest, including SDK-added services, providers, receivers, permissions and deep links. Verify external intents cannot silently start capture or complete an untrusted auth callback. |
| Data | Backup disabled; no reference photos committed. Models bundled and local. | Publish an accurate privacy policy, verify actual retention/logging/SDK telemetry, and complete distribution-channel data disclosures. Review clipboard exposure and recipient apps. |
| Permissions | Camera, notifications and Bluetooth connection requested in relevant flows; USB approval is per-device. SDK-contributed microphone, all-files/shared-storage and legacy Bluetooth permissions are removed by manifest rules and checked in the installed package. | Test denial, permanent denial, revocation during capture and notification denial. Review permissions after every SDK update and provide usable recovery instructions. |
| SDK configuration | Meta application ID/token are developer placeholders; mock-device SDK is excluded from release dependencies. | Configure approved production Meta integration or remove it from the public build. Test its authentication and audit vendor data behavior. |
| Dependencies/models | Versions pinned; U2Net license included; model sources/checksums recorded. | Produce an SBOM, review security advisories and every redistribution license, choose/document the project's own distribution license, add complete third-party notices, and establish an update process. A successful dependency resolution is not a vulnerability audit. |
| Release integrity | Debug APK uses a debug key. Release build has shrinking configured. | Manage a private production signing key outside Git, verify update continuity, sign and verify the final APK/AAB, archive hashes/mapping files and test the signed artifact. Never distribute debug signing as production. |
| Native compatibility | USB/AI SDKs package .so libraries. | Run the static 16 KB checker below and a 16 KB Android runtime check. Upgrade/rebuild any failing vendor library; compatibility mode is not release verification. |

The merged release manifest also exports Meta's ACDCRegistrationService without
a manifest permission. This may be an intended registration entry point; review
its SDK-side caller validation before shipping. ProfileInstallReceiver is exported
but protected by android.permission.DUMP. The release app is not debuggable.
Outgoing cleartext traffic is explicitly disabled, but that policy does not
secure the raw HTTP server or change The vOICe's independent client policy.
Do not treat it as protection for the localhost stream.

## Well-behaved Android release gates

### Accepted same-phone sharing decision, 2026-10-11

The project owner explicitly confirmed that other apps accessing the stream is
its intended purpose, with The vOICe for Android as a consumer. Keep the local
MJPEG endpoint unauthenticated; adding a token or restricting consumers is not
required for release. Other apps on the phone can access frames while media is
running, without SightBridge identifying the caller. Document this behavior in
tester/privacy information. This acceptance applies to same-phone access and
does not authorize exposing the camera stream on the LAN or internet.

### Lifecycle and reliability

Keep capture user-initiated, show the camera foreground notification, offer Stop
all, release camera/wake-lock resources when idle, and never auto-resume capture
after process death. The current service uses START_NOT_STICKY and has no boot
restart receiver. Background camera work must remain in the camera foreground
service rather than WorkManager or an ordinary background service. The wake lock
expires after two minutes unless renewed every minute while processing is active;
stop/destruction cancels renewal and releases it immediately.

Run signed-release checks for: Home/Back, dashboard destruction/recreation, screen
off/lock, task dismissal, Android's foreground-service stop control, app force
stop, process death, camera unplug/reconnect, permission revocation, competing
camera apps and other audio. Verify every stop path removes the notification,
closes sockets and releases the wake lock. Activity-driven UI tests cover a subset
of these paths; they do not establish all OEM or process-death behavior.

Measure at least a 60-minute USB+MJPEG+AI session, memory growth, battery drain,
CPU/temperature and throttling; add repeat start/stop and release-mode ANR/crash
checks. Define acceptable latency and frame-rate targets before testing. Verify
edge-to-edge insets, predictive Back, landscape, small windows and large screens.
Validate ABI coverage on each supported device family. The OnePlus build property
limits artifacts to arm64-v8a; it is not a universal compatibility test.

AI is an optional perceptual aid. Evaluate false names and missing cues on a
consented household dataset containing chairs, toilets, cupboards, barriers and
screen doors in varied lighting and viewpoints. Publish limits; do not claim
obstacle clearance, distance or safe navigation. Salient regions may include
surfaces/backgrounds and are not confirmed physical objects.

## Accessibility target and evidence

Target **WCAG 2.2 Level AA**, applying [W3C WCAG2ICT guidance](https://www.w3.org/TR/wcag2ict-22/)
to native software and following Android accessibility guidance. WCAG2ICT is
informative guidance, not a separate certification. Do not claim conformance
until every applicable Level A and AA success criterion is assessed against the
final signed build and complete user tasks. WCAG 3 drafts are not a substitute
for the WCAG 2.2 assessment. AAA improvements may be added where practical.

Existing evidence: radio selected-state semantics, named standard buttons,
headings, minimum 48dp targets, scrollable scalable text, status live regions,
contrast unit checks, conditional controls, and TalkBack announcement routing.
The historical 200% font check was on an earlier build and must be repeated on
the current version. Automated semantics tests do not confirm actual spoken
order, focus visibility or complete tasks using assistive technology.

Create a criterion-by-criterion record containing applicability, build/device,
test steps, observed result, evidence and unresolved defects. The following
groups identify the required checks; they are not marked as conformance passes.

| WCAG area / criteria | Assessment still required |
| --- | --- |
| Text alternatives: 1.1.1 | Check every actionable icon, radio option, notification and permission explanation. Document decorative/visual-camera-content scope and equivalent text cues. |
| Time-based media: 1.2.1–1.2.5 | Determine applicability to live camera output and spoken cues; document any exceptions rather than assume an audio/visual equivalent meets every criterion. |
| Relationships/order: 1.3.1–1.3.5 | Check grouped radios, headings, names/roles/states, reading order, orientation and meaning beyond screen position. Input-purpose applicability must be recorded. |
| Color/audio/contrast: 1.4.1–1.4.6, 1.4.10–1.4.13 | AA review covers 1.4.1–1.4.5 and 1.4.10–1.4.13; 1.4.6 is an optional AAA improvement. Verify text and control contrast, non-color status, 200% text, reflow, spacing applicability, images of text, audio stopping and content on focus/hover. |
| Keyboard/no traps: 2.1.1, 2.1.2, 2.1.4 | Complete every flow with a hardware keyboard and Switch Access. Check single-character shortcuts and all system/dialog transitions. |
| Timing/pause: 2.2.1, 2.2.2 | Verify no timed permission or confirmation response; user can stop AI updates/audio; announcements do not continuously disrupt other tasks. |
| Flashing: 2.3.1 | Inspect UI and determine applicability to camera/external media. |
| Navigation/focus: 2.4.1–2.4.7, 2.4.11 | Evaluate software applicability of bypass/title/multiple ways; check logical order, control purpose, visible focus and focused controls unobscured by app bars, dialogs or added URL/result content. |
| Pointer/target/input: 2.5.1–2.5.4, 2.5.7, 2.5.8 | Verify single-action alternatives, pointer cancellation, spoken-label match, no motion-only action, no drag-only action and sufficient target size. No camera-holding/typing combination should be required for ordinary app operation. |
| Language: 3.1.1, 3.1.2 | Verify programmatic application/text language and pronunciation; move hardcoded user-facing strings to resources and review any mixed-language output. |
| Predictability/help: 3.2.1–3.2.4, 3.2.6 | Verify no unexpected operation on focus/source selection; consistent controls and any help. Adding URL/results must not steal focus. |
| Errors/input/auth: 3.3.1–3.3.4, 3.3.7, 3.3.8 | Test permission, USB, model, server and speech failures with accessible recovery. Assess redundant-entry/error-prevention/authentication applicability, including Meta flows if shipped. |
| Interoperability/status: 4.1.2, 4.1.3 | Check all names/roles/values, selected/disabled/running states, actual TalkBack status speech, duplicate clipboard speech, and no app-TTS overlap. WCAG 2.2 removes 4.1.1. |

Test manually with TalkBack, Switch Access, keyboard and magnification, including
200% text, large display scaling, portrait/landscape and permission/error cases.
Include blind and low-vision testers and people with limited dexterity; keep
camera positioning separate from text-entry tasks. Record speech interference
with The vOICe. Supply accessible tester instructions, help and a defect-report
route. Publish a scoped accessibility statement only after evaluating the build.

## Reproducible build and static checks

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
python3 scripts/check_native_alignment.py app/build/outputs/apk/release/sightBridge-release.apk
```

For a OnePlus-only validation artifact add `-PsightbridgeAbi=arm64-v8a`.
Use the installed build tools' `zipalign -c -P 16 -v 4` as an independent APK
packaging check. The Python check examines 64-bit ELF LOAD segments and stored
library ZIP offsets; it cannot substitute for running on a 16 KB device.

Before public distribution, set up CI to run build, unit tests, lint, release
artifact checks and dependency review, with protected branch review. Meta SDK
downloads need a narrowly scoped GitHub Packages credential in CI secrets;
never copy local.properties or credentials into Git. Add device tests separately
and retain release evidence. Keep debug tester artifacts separate from a signed
production release. No production signing or store publication is performed by
this change.

## Sources reviewed

- [WCAG 2.2](https://www.w3.org/TR/WCAG22/) and [WCAG2ICT](https://www.w3.org/TR/wcag2ict-22/)
- [Android Compose accessibility testing](https://developer.android.com/develop/ui/compose/accessibility/testing)
- [Android 16 target behavior](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Android API/toolchain compatibility](https://developer.android.com/build/releases/about-agp)
- [AGP 8.13 release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [16 KB compatibility](https://developer.android.com/guide/practices/page-sizes)
- [Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)

## Final validation for this change

The final API 36 debug and shrunk release APKs built successfully with AGP 8.13.2
and Gradle 8.13. All 46 JVM tests passed. Debug lint reported zero errors and
52 warnings (including dependency modernization and resource/style recommendations);
release vital lint passed. Warnings remain review work rather than being suppressed.

The old CameraX 1.3.2 image-processing library failed the native check with 4096-byte
LOAD alignment. After upgrading CameraX to 1.6.2, both handset APKs passed static
16 KB checks across all 83 packaged arm64 libraries, and the build tools' 16 KB
ZIP alignment checks passed. The OnePlus itself uses 4096-byte pages, so execution
on a 16 KB Android runtime remains unverified. This is an explicit release gate.

The release manifest confirms minimum/target/compile API 36, version code 3,
no debug flag, backups disabled, outgoing cleartext disabled, and absence of the
five removed SDK permissions. Release dependency inspection found no mock-device
SDK. Debug signing verifies. The production release APK is deliberately unsigned:
production signing and a signed-release device run are pending. A separate
release-style `com.sightbridge.testing` APK can now be signed with the persistent
testing key; see [signed testing](SIGNED_TESTING.md). Its testing identity is not
production signing approval or a completed signed-build hardware assessment.

The code-7 signed testing build is now installed in place of the debug app.
Actual-device checks confirmed dashboard startup, USB camera access, AI position
output and concurrent localhost MJPEG streaming (65 JPEG frames in three seconds).
R8 rules now preserve MediaPipe/Flogger/protobuf and ONNX native lookup targets;
the lifecycle owner is supplied explicitly to Compose. These checks cover basic
signed-build functionality, not the full reliability or accessibility matrix.

The earlier debug APK was tested on the OnePlus. The combined device suite passed
(ten active checks, including merged permissions, plus one skipped deployment-only
check). All five dashboard checks also passed at 200% text size. The original
135% text size was restored. These automated checks do not complete the manual
WCAG 2.2 AA assessment or the security review above.

The repository was checked for obvious access-token/private-key patterns before
commit; no matches were found. This bounded check is not a complete secret-history
or dependency-vulnerability audit. Local properties and APK outputs remain ignored.
