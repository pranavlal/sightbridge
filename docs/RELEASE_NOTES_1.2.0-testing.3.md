# SightBridge 1.2.0 — Testing pre-release 3

Signed Android testing build for Android 16 or later on arm64-v8a devices.
This updates the earlier `com.sightbridge.testing` installation: Android version
code 10, app version `1.2.0-testing`. It uses the same testing signing key.

## Changes

- Embed the Wearables Developer Center application ID and client token in the
  signed APK's manifest from ignored local build configuration. The `sightbridge`
  registration callback scheme remains in place.
- Keep the client token out of GitHub source files and increment the Android
  version code so the prior testing APK can update.
- Explain Meta's current private Beta channel requirement for glasses access
  without Developer Mode.

## Verification

The signed APK passed its signature check, release vital lint, 16 KB ZIP and
native-library alignment checks. Its package, version, DAT manifest metadata,
and callback scheme were checked in the final APK. No phone or physical glasses
were attached for this release, so registration with Developer Mode off and the
Meta glasses video path remain unverified.

APK SHA-256: `814f431b28dd8666281802703d626a0cf7ac7ca1ddf0bdd68ffeb235d44617fa`

Meta's DAT release channels are currently invite-only. To use the glasses
without Developer Mode, the project must have a current integration version
assigned to Beta, and the tester must accept an invitation for their Meta
account. Downloading the APK alone does not give every user glasses access.
See [Meta's release channel guidance](https://wearables.developer.meta.com/docs/develop/dat/set-up-release-channels/).

This is a testing release. See [signed testing instructions](https://github.com/pranavlal/sightbridge/blob/v1.2.0-testing.3/docs/SIGNED_TESTING.md)
and the [security and accessibility review](https://github.com/pranavlal/sightbridge/blob/v1.2.0-testing.3/docs/SECURITY_ACCESSIBILITY_REVIEW_2026-10-14.md).
