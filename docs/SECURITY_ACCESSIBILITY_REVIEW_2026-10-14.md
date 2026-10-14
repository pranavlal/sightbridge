# Security and accessibility review — 2026-10-14

This review covers the changes staged for the next testing build. It includes
source and manifest inspection, Android lint, JVM tests, and device tests on a
OnePlus CPH2573 running Android 16 with a USB `openaicam` webcam and TalkBack.
It is not a penetration test or WCAG conformance assessment.

## Findings and fixes

| Finding | Resolution | Verification |
| --- | --- | --- |
| Android instrumentation normally suppresses accessibility services, making TalkBack appear to switch off during tests. | A custom test runner requests `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. App code does not change accessibility settings. | The connected-phone suite kept TalkBack enabled. The AI test recorded one accessibility announcement and zero app TTS utterances. |
| The selected phone TTS voice could require a network connection, despite the offline speech promise. | App TTS accepts the phone's selected default voice only if it is installed and does not require a network connection. Otherwise the description stays visible and the app shows a speech warning. | JVM checks cover missing, network, uninstalled and local voices; the device suite checks the TalkBack route. |
| An unused adapter mode could bind the MJPEG feed to the LAN over plain HTTP with a token in the URL. | Removed the LAN configuration and token URL path; the server always binds to `127.0.0.1`. | JVM server tests assert loopback binding; the USB device test receives frames through the local URL. |

## Verification

- Android debug and release builds, debug test APK, 51 JVM tests and Android
  lint passed. Lint reported 41 warnings and zero errors.
- Eight ground-hazard research tests passed; its Python sources compiled.
- On the connected phone, the 12-test instrumentation suite passed with the
  USB webcam attached. Eleven tests ran; the optional test that intentionally
  leaves capture running was skipped by its own assumption.
- A scan of changed source and documentation found no common private-key or
  access-token patterns. `git diff --check` passed.

## Remaining limits

The localhost feed intentionally permits other apps on the same phone, including
The vOICe for Android, to read frames while media output runs. This behavior was
accepted by the project owner on 2026-10-11. The Meta glasses video path cannot
be tested without physical glasses. A manual TalkBack and broader accessibility
assessment, signed-build security review, and production dependency/advisory
review remain release work; these test results do not certify their absence of
issues.
