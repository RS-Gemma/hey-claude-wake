# Development plan

## Project definition

- **Purpose:** Provide a standalone Android wake-phrase service that can dispatch a local action.
- **Target platform/device:** Samsung Galaxy A15 5G (SM-A156U), Android 16.
- **Technology direction:** Native Kotlin Android app; local wake-word detection; visible microphone foreground service.
- **Distribution:** Versioned GitHub Releases; APKs are not committed to source control.

## Stage 1 — single wake phrase/action

Complete according to the owner's physical-device report for v1.0.2:
- “Hey Jarvis” detection at threshold 0.35.
- Detection notification.
- Tapping notification opens ChatGPT.

## Stage 2 — background-process resilience

Version 1.1.0 preserves the detector and returns START_STICKY after successful foreground-service startup. Startup and microphone preflight failures remain non-sticky. The persistent notification has a Stop action.

Owner-reported test: true detections consistently triggered notifications across screens, including the lock screen; one false positive was observed; battery drain was not noticeable. Process-reclamation recovery was not separately confirmed.

## Stage 3 — direct ChatGPT Voice launch experiment

### Goal
Attempt to open ChatGPT Voice automatically on wake detection, including when the app is backgrounded or the screen is locked.

### Reference and technical constraints
The open-source [GPTWake project](https://github.com/suddenBook/GPTWake) documents an approach using ChatGPT's currently observed internal activity com.openai.voice.assistant.AssistantActivity, with a best-effort link fallback. The component is undocumented and is not a supported public integration API. This project's implementation is independently written and limited to a small launch helper; it does not import GPTWake's wake engine or UI.

Modern Android restricts background activity launches. This experiment requests optional Display over other apps permission and tries the direct launch only when the user grants it. For microphone access while the device is locked, GPTWake's report indicates ChatGPT may need to be the system Digital assistant. Whether that option is available and whether this method works on the Galaxy A15 must be tested.

### Implementation in v1.3.0
- Preserve the existing local wake-word pipeline, 0.35 threshold, and Stage 2 successful-start START_STICKY behavior.
- On a detection, post the existing fallback notification.
- Stop and release this app's detector/microphone before attempting ChatGPT Voice launch.
- If Display over other apps permission is granted, check whether ChatGPT's observed Voice activity is exported and unprotected, then attempt to launch it.
- If unavailable, try a best-effort Voice deeplink.
- Stop listening after detection; require manual restart after the conversation. Automatic microphone reacquisition is explicitly deferred.
- Do not add root, device-admin, Accessibility automation, boot auto-start, or a hidden always-visible overlay.

### Physical-device acceptance gate
On the Samsung Galaxy A15 5G (SM-A156U), Android 16:
1. Confirm ChatGPT is installed, signed in, and has microphone permission.
2. Grant Display over other apps permission to this app.
3. Determine whether Android offers ChatGPT as the system Digital assistant; record whether selecting it is necessary or possible.
4. Start listening with the screen on, say “Hey Jarvis,” and record whether ChatGPT Voice starts without tapping.
5. Repeat from another app and with the screen locked.
6. If direct launch fails, confirm the fallback notification opens ChatGPT normally.
7. Confirm this app releases the microphone and listening stops after detection.
8. End the ChatGPT conversation and manually restart listening; confirm the wake detector works again.
9. Test with Display over other apps permission denied and confirm the app does not crash; notification fallback should remain usable.
10. Record any ChatGPT version-specific behavior. Do not treat CI success as device verification.

### Known limitations
- ChatGPT's internal Voice activity is undocumented and may be renamed, unexported, or permission-protected in a future release.
- startActivity() may be blocked by Android without reliably throwing an exception; a successful call is not proof Voice became active.
- Voice microphone capture under keyguard may depend on ChatGPT being the system Digital assistant. If ChatGPT is not available as a Digital assistant on this device, screen-locked voice may not work.
- The service stops after a detection and does not automatically resume listening.
- The user must explicitly grant Display over other apps permission; without it the app falls back to a tap-to-open notification.
- CI build success is not evidence of direct Voice launch, lock-screen capture, or battery behavior.

## Release and test gate

Stage 3 release workflow: .github/workflows/release-1.3.0.yml. APKs are attached to immutable versioned GitHub Releases, not committed to source. Stage 3 is not complete until physical-device acceptance results are recorded.
