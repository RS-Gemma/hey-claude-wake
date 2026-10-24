# Development plan

## Project definition

- **Purpose:** Provide a standalone Android wake-phrase service that can later dispatch configurable actions.
- **Target platform/device:** Android; initial physical target is Samsung Galaxy A15 5G (SM-A156U), reported to run Android 16.
- **Primary user:** Device owner.
- **Technology direction:** Native Kotlin Android app; local wake-word detection; visible microphone foreground service.
- **Distribution:** Versioned GitHub Releases; APKs are not committed to source control.

## Stage 1 scope

1. Install and launch a minimal native Android application.
2. Let the user grant microphone permission and explicitly start/stop listening from the visible app.
3. Detect one built-in wake phrase, “Hey Jarvis,” using the open-source openWakeWord Android library and its bundled model.
4. On detection, post a notification with an action that opens the official ChatGPT Android app when tapped.
5. Display current listening state and document setup, permissions, build, limitations, and release procedure.
6. Build a signed-by-Android-debug-keystore APK suitable for sideloading and publish it as a GitHub Release.

## Explicit exclusions for Stage 1

- No custom wake-phrase training or model import.
- No user-configurable phrase-to-action mappings.
- No arbitrary speech recognition or command parsing.
- No guarantee of direct background launch into ChatGPT Voice mode; the detection action is a notification the user taps.
- No auto-start microphone service at boot.
- No accessibility-service automation, full-screen intent, or device-admin workaround.
- No cloud audio processing, analytics, accounts, ads, paid features, or application telemetry.
- No formal project board, resource-planning document, decision-tracing system, research-provenance subsystem, separate validation plan, public documentation vault, or dedicated security document; these optional systems were left disabled in the shared new-project template.

## Architecture

- **MainActivity:** Permission request, explicit Start/Stop controls, current status, and setup instructions.
- **WakeWordService:** User-started microphone foreground service with persistent notification.
- **Wake-word detector:** App-local patched copy of `openwakeword-android:0.1.0` pipeline, using its bundled HEY_JARVIS model and ONNX Runtime assets. The original dependency remains for bundled assets/runtime; the app's patched class drops the three mel frames generated from each 480-sample overlap.
- **Action:** A detection notification opens ChatGPT only after the user taps it. This avoids relying on background activity launches that Android may block.
- **Release:** Dedicated .github/workflows/release-1.0.0.yml; build output is attached to a GitHub Release, not committed.

## Readiness gates

### Stage 1 implementation gate

- [ ] Project source and build configuration committed.
- [ ] Dependency resolution and Gradle build succeed in CI.
- [ ] Streaming mel features append only non-overlapping frames from each audio chunk.
- [ ] APK is generated and installable.
- [ ] Manifest/service permissions comply with current Android foreground microphone-service requirements.
- [ ] README and third-party model licensing notices are present.

### Stage 1 device-verification gate

- [ ] Install on the Samsung Galaxy A15 5G (SM-A156U).
- [ ] Grant microphone and notification permissions.
- [ ] Start listening while app is visible; confirm persistent service notification.
- [ ] Say “Hey Jarvis” and confirm detection triggers the action notification.
- [ ] Tap the action notification and confirm ChatGPT opens.
- [ ] Stop listening and confirm microphone capture stops.
- [ ] Check false positives, lock-screen behavior, thermal/battery impact, and behavior under Samsung battery management.

**Release readiness is not equivalent to device verification.** CI can verify compilation and APK creation, but only physical-device testing can close the device-verification gate.

## Known risks and deferred decisions

- **Model license:** The library's bundled pretrained wake-word models are CC BY-NC-SA 4.0, despite the library code being Apache-2.0. This model is not suitable for commercial use. Replace it with a suitably licensed model before commercial distribution.
- **Background restrictions:** Android requires microphone foreground services to be started under allowed conditions and with required permissions. The user must start listening from the visible app.
- **ChatGPT Voice:** The official app does not expose a verified public intent for direct Voice-mode activation. Stage 1 opens ChatGPT via a user-tapped notification; direct Voice activation is deferred.
- **Device testing:** This development environment has no Android SDK/Gradle installation and cannot run the APK on the target phone. CI and device results must be reported separately.
