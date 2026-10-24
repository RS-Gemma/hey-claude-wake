# Development plan

## Project definition

- **Purpose:** Provide a standalone Android wake-phrase service that can later dispatch configurable actions.
- **Target platform/device:** Android; physical target is Samsung Galaxy A15 5G (SM-A156U), Android 16.
- **Primary user:** Device owner.
- **Technology direction:** Native Kotlin Android app; local wake-word detection; visible microphone foreground service.
- **Distribution:** Versioned GitHub Releases; APKs are not committed to source control.

## Stage 1 — single wake phrase/action

Stage 1 is complete according to the project owner's physical-device report for v1.0.2:

- The service listened for “Hey Jarvis” at a 0.35 threshold.
- Detection produced the action notification.
- Tapping the notification successfully opened ChatGPT.

The v1.0.2 inference correction drops three mel frames generated from the 480-sample overlap between consecutive audio windows.

## Stage 2 — background-process resilience

### Scope in v1.1.0

1. Preserve the Stage 1 wake-word pipeline, 0.35 threshold, detection notification, and ChatGPT action.
2. Return `START_STICKY` after successful foreground-service startup so Android may recreate the service if it reclaims the app process.
3. Keep startup and microphone preflight failures non-sticky.
4. Preserve explicit user start/stop and the persistent notification's Stop action.
5. Document a physical-device test plan for backgrounding, screen-off operation, process reclamation, and battery-management behavior.

### Explicit exclusions

- No automatic start after reboot.
- No attempt to restart after the user force-stops the app.
- No accessibility-service automation, device-admin workaround, or exemption from Android/Samsung restrictions.
- No custom wake phrase/model, configurable action mapping, arbitrary speech recognition, or automatic ChatGPT Voice activation.
- No cloud audio processing, analytics, accounts, ads, or paid features.
- No claim of measured battery life or uninterrupted listening until the target-device tests are completed.

## Architecture

- **MainActivity:** Permission request, explicit Start/Stop controls, current status, and setup instructions.
- **WakeWordService:** User-started microphone foreground service with persistent notification; v1.1.0 uses `START_STICKY` after successful initialization.
- **Wake-word detector:** App-local patched copy of the openWakeWord Android pipeline using its bundled HEY_JARVIS model and ONNX Runtime assets. Three mel frames from each overlapping audio window are discarded before appending to the rolling mel buffer.
- **Action:** Detection notification opens ChatGPT only after the user taps it.
- **Release:** Dedicated workflow per version; APK attached to GitHub Release, not committed to source.

## Stage 2 CI/build gate

- [ ] Version code/name updated to 4 / 1.1.0.
- [ ] Successful service startup returns `START_STICKY`.
- [ ] Microphone preflight and startup failures still return non-sticky and stop the service.
- [ ] Gradle debug build succeeds in GitHub Actions.
- [ ] APK is published as v1.1.0 with expected filename.
- [ ] Release notes and this plan distinguish CI build from physical-device verification.

## Stage 2 physical-device acceptance gate

On the Samsung Galaxy A15 5G (SM-A156U), install v1.1.0 and verify:

1. Start listening from the visible app and confirm the persistent notification appears.
2. Background the app and lock the screen; say “Hey Jarvis” and confirm the action notification appears.
3. Tap the detection notification and confirm ChatGPT opens.
4. Tap Stop in the persistent notification and confirm listening stops.
5. Leave the app listening with screen off for a defined period and record start/end battery percentage; compare with a similar idle period if practical.
6. Observe whether Android/Samsung battery management stops the listener.
7. If Android reclaims the process, observe whether the foreground service is recreated and wake detection resumes. This may be difficult to trigger reliably and is not guaranteed.
8. Confirm a user-initiated force-stop does not silently restart the app.

The owner has completed the Stage 1 flow on v1.0.2. Stage 2 background and battery behavior remains unverified until these checks are run on the physical device.

## Known limitations

- `START_STICKY` asks Android to recreate a killed service; it does not guarantee it, preserve microphone access under every system state, or restart after force-stop/reboot.
- Android requires microphone foreground services to be started under permitted conditions. Start listening while the app is visible.
- The official ChatGPT app does not expose a verified public intent for direct Voice-mode activation.
- CI build success is not evidence of device behavior or battery performance.
