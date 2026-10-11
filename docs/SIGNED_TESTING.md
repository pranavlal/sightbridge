# Signed testing build

SightBridge Testing is a release-style, non-debuggable build signed with a
dedicated persistent testing key. It uses application ID `com.sightbridge.testing`
and label **SightBridge Testing**, so it can coexist with the existing
debug app `com.sightbridge`. Version 1.2.0-testing requires Android 16 (API 36).
The default build targets arm64-v8a, matching the connected OnePlus.

## Build and verify

On the configured Linux development machine:

```sh
python3 scripts/build_signed_testing.py
```

The helper creates the key only when absent, builds the `qa` variant with
release shrinking enabled, signs it, verifies its certificate/signature and
16 KB alignment, and writes a SHA-256 file. It requires JDK 17, SDK platform 36,
build tools 35.0.0 and the existing dependency-download configuration.
Use `--abi` to choose another supported ABI; that ABI needs its own runtime checks.

Share **`app/build/outputs/apk/qa/sightbridge.apk`** and optionally
`sightbridge.apk.sha256`. Do not share `sightbridge-unsigned.apk`.
APK signing proves integrity and continuity of updates; it does not complete
production security, accessibility or hardware validation.

## Key custody and future updates

The default private directory is:

```text
~/.local/share/sightbridge/signing/testing/
    sightbridge-testing.p12
    keystore-password
```

The directory is owner-only (0700), and both files are owner-only (0600).
The keystore is password protected with a randomly generated password; the
password file allows signing without exposing the password in shell arguments
or build logs. Both files are sensitive. Filesystem access control is the
protection for this unattended local signing setup; storing them together does
not provide a second independent factor.

**Back up both files securely**, preferably in encrypted storage, before relying
on this key for long-term updates. This helper does not create an independent
backup. Do not upload either file or paste its contents into chats or GitHub.
Keystore extensions and signing directories are also ignored as a secondary
defense. A certificate fingerprint and APK checksum are public information.

Rerunning the helper reuses the same key. Increase the app version code for
future distributed updates. Losing/replacing the key prevents ordinary updates
to existing testing installations; do not regenerate it as a troubleshooting
step. Override `--signing-dir` only to use an existing securely stored persistent
key directory. Temporary and repository-local key directories are rejected.

This is a testing identity, separate from future production signing. A public
production app needs its own signing/distribution decision, credentials, update
strategy and signed-release device checks.

## Tester installation and operation

- Install the signed `sightbridge.apk` through Android's normal package installer.
  Android may still ask for permission to install from that browser/file manager
  or show Play Protect/developer-verification warnings. Signing does not bypass
  those checks.
- The testing app has its own data and permissions. Existing debug app data is
  not migrated and the debug installation need not be uninstalled.
- Stop media and AI in the existing SightBridge app before starting them in
  SightBridge Testing. They share the physical camera and localhost port 8080;
  simultaneous processing is not supported.
- The vOICe can use the same localhost streaming URL when the testing server
  runs. Other local apps can access it by the owner's accepted design.
- The testing key and separate package do not make experimental Meta developer
  configuration production-ready. Meta authentication and the final signed-build
  hardware/accessibility assessment remain pending.

See [release readiness](RELEASE_READINESS.md) for the broader release gates.

## Initial signed artifact, 2026-10-11

- Package: com.sightbridge.testing; version 1.2.0-testing, code 3; Android 16+, arm64-v8a.
- Signature verified using APK Signature Scheme v3; one RSA-3072 signer.
- Public signing-certificate SHA-256 fingerprint:
  `820948c52bb2a3a2849b3d29badd171e4c3b8b17639fcc2983101ebd4c56db7a`.
- Initial APK SHA-256:
  `bcce1dd1f1c49646002cc4448c6afc9353687123b3d240984f776d06c87524bd`.
  Future rebuilds can have different APK hashes; the certificate must remain the same for updates.
- Release vital lint, signed APK ZIP alignment and all 83 arm64 native-library
  alignment checks passed. The merged package has no debug flag or removed
  microphone/shared-storage permissions. Private signing files have owner-only permissions.
- This artifact has not yet completed signed-build device/hardware testing.

## Signed-build compatibility fixes

The initial code-3 artifact failed during dashboard composition after R8 removed
the legacy Compose/Lifecycle reflection bridge. The dashboard now supplies its
lifecycle owner explicitly. Hardware testing also exposed MediaPipe Flogger
stack inspection and native protobuf field lookup failures; the application
keeps those SDK classes and members through shrinking. These changes follow the
[upstream MediaPipe R8 issue](https://github.com/google-ai-edge/mediapipe/issues/6138).
The ONNX Runtime classes are also preserved as required by its
[Android R8 instructions](https://onnxruntime.ai/docs/build/android.html), after
the signed build exposed a JNI class lookup failure during foreground inference.

To check the actual installed signed APK's dashboard, use
`python3 scripts/check_signed_startup.py`. The phone must be unlocked with
installer/permission dialogs closed. `--restart` stops any running camera session.
This startup check does not replace media/AI hardware validation.

## Installed testing artifact, 2026-10-11

- Version 1.2.0-testing, Android version code 7; same testing certificate above.
- APK SHA-256: `0c6e7d72af62695b65386735cf8422417d4f59a0b730f8f3660907395b3deb3e`.
- Installed on the OnePlus through its normal confirmation screen using ADB UI
  interaction. The previous debug app and instrumentation package were removed.
- Dashboard startup, USB permissions, AI inference with position output and
  concurrent media streaming were checked on the actual non-debuggable APK.
  The localhost MJPEG stream returned 65 complete JPEG frames in three seconds.
- Signature, release vital lint and static alignment of 83 native libraries passed.
  Full long-duration, permission-denial and accessibility assessment remain pending.

## Shorter streaming URL update, 2026-10-12

- Version 1.2.0-testing, Android version code 8, signed with the existing testing certificate.
- Displayed and copied URL: `http://localhost:8080/mjpeg`.
- `/live.mjpeg` remains available for existing consumers with the same access checks.
- Eight server, adapter and end-to-end streaming tests passed. Release vital lint,
  APK signature and 16 KB alignment checks passed for all 83 native libraries.
- APK SHA-256: `91c847eef95756f275a680f45d9d0815cd6f469f6dede99ee8dcde59b1ef12f0`.
- Installation confirmed on the connected OnePlus: version code 8. The updated
  dashboard launched and remained the resumed activity with a running process.
  Streaming from the installed update has not yet been checked with a live camera.
- Rebuilt for the GitHub update: all 47 Android unit tests and eight research
  tests passed; the signed APK was reverified with the same certificate and checksum.
