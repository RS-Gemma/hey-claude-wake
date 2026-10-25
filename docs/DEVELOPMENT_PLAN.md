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
5. Document physical-device tests for backgrounding, screen-off operation, process reclamation, and battery-management behavior.

### Owner-reported result
The owner reports true detections consistently triggered notifications across screens, including the lock screen; one false positive was observed and battery drain was not noticeable. Process-reclamation recovery has not been confirmed as a distinct test.

### Explicit exclusions
- No automatic start after reboot.
- No attempt to restart after the user force-stops the app.
- No accessibility-service automation, device-admin workaround, or exemption from Android/Samsung restrictions.
- No custom wake phrase/model, configurable action mapping, arbitrary speech recognition, or automatic ChatGPT Voice activation.
- No cloud audio processing, analytics, accounts, ads, or paid features.
- No claim of measured battery life or uninterrupted listening.

## Stage 3 — ChatGPT Voice handoff compatibility test

### Objective
Test whether the official ChatGPT Android app's documented **Settings → Voice → Start with Voice** setting can reduce the interaction required after wake-word detection.

### Implementation in v1.2.0
- Preserve the Stage 1 detector, 0.35 threshold, Stage 2 sticky service behavior, and explicit Stop action.
- Explain the supported ChatGPT setting in the app UI.
- Clarify in the detection notification that ChatGPT's Start with Voice setting may be available.
- Do not add undocumented ChatGPT intent extras, Accessibility automation, full-screen notification tricks, or background activity launches.

### Known platform limitation
OpenAI's help documentation says Start with Voice starts Voice automatically when ChatGPT opens to a new or empty conversation. It does not document a public Android intent for a third-party app to force Voice mode. The existing notification action opens ChatGPT only when the user taps it. It is not a fully hands-free activation path, and whether Voice starts may depend on ChatGPT's current state, account, and app version.

A truly hands-free voice interface inside this project would be a materially different architecture (for example, a custom voice client using an API) and may introduce API credentials and usage costs. That is not silently included in this stage.

### Physical-device acceptance gate
On the Samsung Galaxy A15 5G (SM-A156U), Android 16:
1. Confirm ChatGPT Settings → Voice offers **Start with Voice**; enable it if present.
2. Start the listener in this app.
3. Test with ChatGPT closed, in the background, and already showing a conversation.
4. Say “Hey Jarvis,” tap the detection notification, and record whether Voice starts automatically in each state.
5. Confirm normal ChatGPT launch still works when Start with Voice is disabled.
6. Confirm the persistent Stop action still stops listening.
7. Report any behavior that differs between ChatGPT app states. Do not infer success from CI.

### Stage 3 readiness gate
The handoff experiment is complete only when the owner reports the physical-device results. A successful APK build alone does not prove Voice-mode activation.

## Architecture

- **MainActivity:** Permission request, explicit Start/Stop controls, current status, and setup guidance.
- **WakeWordService:** User-started microphone foreground service with persistent notification; v1.1.0+ uses `START_STICKY` after successful initialization.
- **Wake-word detector:** App-local patched copy of the openWakeWord Android pipeline using its bundled HEY_JARVIS model and ONNX Runtime assets. Three mel frames from each overlapping audio window are discarded before appending to the rolling mel buffer.
- **Action:** Detection notification opens ChatGPT only after the user taps it. No supported direct Voice-mode intent is known.
- **Release:** Dedicated workflow per version; APK attached to GitHub Release, not committed to source.

## Known limitations

- `START_STICKY` asks Android to recreate a killed service; it does not guarantee it, preserve microphone access under every system state, or restart after force-stop/reboot.
- Android requires microphone foreground services to be started under permitted conditions. Start listening while the app is visible.
- The ChatGPT Start with Voice setting is controlled by ChatGPT and may vary by app/account state.
- The official ChatGPT Android app does not document a public intent for third-party apps to force Voice mode.
- CI build success is not evidence of device behavior or battery performance.
