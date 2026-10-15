# SightBridge Privacy Policy

**Effective date: 15 October 2026**

SightBridge is an Android app that uses a camera to provide a live video feed to apps on the same phone and, if you turn it on, spoken and on-screen object cues. The developer does not receive your camera feed, AI results, or usage history through SightBridge. The app has no account, advertising, or developer-operated cloud service. A third-party SDK used by the app may collect diagnostic data, as explained below.

## What the app accesses and why

- **Camera images:** With your permission, SightBridge uses a phone camera, connected USB camera, or compatible Meta AI glasses camera. Frames are processed on the phone. The object-detection and foreground models are included in the app and run locally; camera images and AI results are not sent to the developer or to a remote AI service.
- **Bluetooth and Meta glasses access:** If you choose Meta glasses, SightBridge uses Bluetooth and the Meta Wearables Device Access Toolkit to connect to the glasses. Registration and camera permission are handled through the separate Meta AI app.
- **Notifications and background operation:** A foreground-service notification shows when processing continues in the background and provides a Stop all action. The app may keep the processor awake while a user-started camera session is active.
- **Local app setting:** SightBridge stores a flag on the phone recording whether its first-run Android permission request has been shown. Android and Meta AI separately manage the permissions and registration you grant.

SightBridge does not request microphone or shared-storage access for these features.

## Local video sharing

If you start **Media Server**, SightBridge makes live camera frames available at `http://localhost:8080/mjpeg`. The server listens only on the phone's loopback address (`127.0.0.1`); it does not expose the feed to the local network or the internet. The feed has no per-app password, so **any app on the same phone that connects to that address can receive frames while the server is running**. This includes apps you choose to use with SightBridge, such as The vOICe for Android. Stop Media Server or use the notification's Stop all action to stop the feed.

The normal Android build declares `INTERNET` permission to support this local HTTP connection and its included SDKs. That permission by itself does not mean SightBridge uploads camera frames. SightBridge's own code does not send frames or AI results to an internet server. The separate `offlineCheck` verification build removes that permission and runs the models locally. The normal build should not be described as completely offline because of the SDK behavior described below.

An app that receives the local feed may handle it under its own privacy policy, including sending it elsewhere. SightBridge cannot control what that app does after it receives frames.

## Meta AI and phone services

Using Meta glasses requires the Meta AI app and Meta's device services. Those services may use the internet and handle registration or device information under Meta's own policies. SightBridge has opted out of the Meta toolkit's optional analytics and SDK crash reporting, but this policy does not cover data handled independently by Meta AI or the glasses.

For spoken cues, SightBridge uses an installed offline Android text-to-speech voice or sends accessibility announcements to an enabled screen reader. Those phone services are controlled by their providers and your device settings. SightBridge does not upload descriptions for speech.

## Google ML Kit diagnostics

SightBridge uses Google's ML Kit object-detection SDK for on-device position cues. [Google's ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) says its Android SDKs collect device and app information, per-installation identifiers, performance and API metrics, event types, and error codes for diagnostics and usage analytics. Google also says the object-detection SDK uses Firebase Remote Config and Firebase Installations. Google says this collected data is encrypted in transit. The developer does not receive this SDK diagnostic data. Camera images and object-detection results are processed on the phone; the disclosure concerns SDK diagnostics, not an image-upload feature.

## Storage, security, and deletion

SightBridge processes ordinary camera frames and AI results in memory and does not save a recording or a history of them. It does not transmit them to the developer. The app disables Android backup and device transfer for its app data. The first-run permission flag remains on the phone until the app's data is cleared or the app is uninstalled. Android may retain permission state or local diagnostic logs according to your device settings; SightBridge does not send those logs to the developer.

You can stop camera access in the app, revoke permissions in Android or Meta AI, clear SightBridge's app data, or uninstall the app. There is no SightBridge account or developer-held app usage record to delete. If you share information with another app or with Meta AI, use that service's controls for its copies.

## Changes and contact

This policy describes the current testing version. If SightBridge's data practices change, the policy will be updated with a new effective date before the changed version is distributed.

If you email [pranav@techesoterica.com](mailto:pranav@techesoterica.com), the developer receives your email address and the information you choose to send. That correspondence is used to respond to you and is kept only as long as needed to handle the inquiry. You can ask for it to be deleted at the same address. Do not post personal information in a public repository issue.
