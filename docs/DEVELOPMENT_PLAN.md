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

## Stage 3 — system assistant overlay launch experiment

### Goal
Invoke the Android-configured digital assistant overlay automatically on wake detection, matching the overlay opened by the user's power-button assistant gesture. Do not launch the full-screen ChatGPT Voice activity as the intended behavior.

### Reference and technical constraints
The open-source [GPTWake project](https://github.com/suddenBook/GPTWake) documents an approach using ChatGPT's currently observed internal activity com.openai.voice.assistant.AssistantActivity, with a best-effort link fallback. The component is undocumented and is not a supported public integration API. This project's implementation is independently written and limited to a small launch helper; it does not import GPTWake's wake engine or UI.

Modern Android restricts background activity launches. This experiment requests optional Display over other apps permission and tries the direct launch only when the user grants it. For microphone access while the device is locked, GPTWake's report indicates ChatGPT may need to be the system Digital assistant. Whether that option is available and whether this method works on the Galaxy A15 must be tested.

### Implementation in v1.3.0 (superseded launch target)
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
4. Start listening with the screen on, say the configured wake phrase, and record whether the system-selected ChatGPT assistant overlay opens without tapping.
5. Repeat from another app and with the screen locked; record whether Android defers assistant operation until unlock.
6. If direct launch fails, confirm the fallback notification opens ChatGPT normally.
7. Confirm this app releases the microphone and listening stops after detection.
8. While listening is active, tap the in-app “Stop listening” button; confirm the persistent notification disappears, status changes to inactive, and the detector no longer triggers. Repeat using the notification's Stop action.
9. End the ChatGPT conversation and manually restart listening; confirm the wake detector works again.
10. Test with Display over other apps permission denied and confirm the app does not crash; notification fallback should remain usable.
11. Record any ChatGPT version-specific behavior. Do not treat CI success as device verification.

### Custom wake phrase requirement

The current detector still loads the bundled pretrained “Hey Jarvis” ONNX classifier. Changing a label or Kotlin string does not change what the model recognizes. The requested phrase is exactly “yo, Ai Overlord”; a real custom classifier must be trained, integrated, and tested before a release can claim to support it. Do not release an APK with a misleading label or silently substitute a different trigger.

### Future development goal — voice-configurable phrases and actions

**Status: planned; not implemented.** The long-term goal is for the owner to tell ChatGPT by voice that a new wake phrase and action should be added. ChatGPT should be able to initiate the workflow hands-free, without requiring the owner to manually visit a model-training website or shuttle files between services.

Target workflow:
1. During a voice conversation, the owner requests a new phrase and describes the action it should invoke.
2. ChatGPT confirms the intended phrase/action and initiates a supported app workflow.
3. The Android app either records phrase examples itself or opens a dedicated, purpose-built recording screen. Recording should guide the owner through several natural repetitions and optional background/noise examples; it must not silently record unrelated conversation.
4. A training service is called through a documented, authenticated API. Training may run in the background and must report progress, failure, and completion. Prefer a self-hostable/open-source pipeline and a design that does not depend on a paid hosted trainer.
5. The trained model is exported in a format and preprocessing contract supported by the Android detector, evaluated for false positives and missed detections, then delivered to the app for installation/activation.
6. The app registers the confirmed phrase-to-action mapping and supports rollback to the previous working model/configuration if validation or installation fails.

Architecture requirements for this goal:
- Treat model training as a programmatic pipeline/API capability, not merely a manual web form or a Colab notebook. A notebook may be a temporary bootstrap for the first model, but must not define the long-term workflow.
- Keep inference local on the phone. Cloud or remote processing is only for the explicitly initiated recording/training workflow, using authenticated transport; clearly disclose any audio sent off-device and allow deletion of retained recordings.
- Keep wake phrases, model artifacts, preprocessing metadata, thresholds, and action mappings versioned together. Never assume arbitrary ONNX files are interchangeable.
- Validate exported models against the app's exact input/output contract and audio preprocessing. Run held-out evaluation and device tests before activating a model.
- Do not allow a spoken request alone to silently register a dangerous or consequential action. Confirm the phrase and action, apply an allowlist/permission model, and require explicit approval for actions with external or destructive effects.
- The system should support adding or changing phrases without rebuilding the entire APK when technically feasible, while retaining a safe recovery path and a clear user-visible way to stop listening.

### Wake-word training pipeline decision

**Preferred direction for this project: LiveKit WakeWord, pending a compatibility prototype.** Its documented open-source training pipeline provides a Python API, synthetic-speech generation, evaluation, and ONNX export; its documentation states that its classifier models are compatible with openWakeWord. That aligns better with the current Android implementation than adopting a model with a different audio front end.

- Project: https://github.com/livekit/livekit-wakeword
- Training documentation: https://docs.livekit.io/agents/multimodality/audio/wakeword/
- The documented Python API is an automation interface, not a hosted public REST API. For the future hands-free workflow, we will need to wrap the pipeline in a small authenticated training service/API or run it in a controlled worker. Do not assume LiveKit provides a free hosted model-training endpoint.
- The Heed Wake Word trainer remains a valid free, easy-to-use standalone option, with Colab/browser workflows and ONNX export. However, its documented custom preprocessing is different from the app's current openWakeWord pipeline, so its exported model is not a drop-in replacement. Do not use Heed for the first production-integrated model unless we deliberately port and test its preprocessing/inference path.
- The immediate “yo, Ai Overlord” model should use the LiveKit/openWakeWord-compatible path if the compatibility prototype confirms that its exported classifier works with the app's existing frontend. This choice avoids creating avoidable integration work for the long-term API-driven feature.

Readiness gates:
1. Generate/export a small custom model for “yo, Ai Overlord” through LiveKit WakeWord.
2. Inspect its ONNX input/output names and tensor shapes, and verify compatibility with the app's 16 kHz audio pipeline and three-stage ONNX inference.
3. Run a reproducible host-side inference/evaluation test using the same preprocessing contract as Android.
4. Integrate the model behind a configurable model asset/path without falsely changing the app's displayed phrase before the model is active.
5. Build and run regression tests, then physically test detection and false-positive behavior on the Galaxy A15.
6. Only after this prototype passes should the project implement the authenticated training API and hands-free phrase/action management workflow.
 
### Known limitations
- ChatGPT's internal Voice activity is undocumented and may be renamed, unexported, or permission-protected in a future release.
- startActivity() may be blocked by Android without reliably throwing an exception; a successful call is not proof Voice became active.
- Voice microphone capture under keyguard may depend on ChatGPT being the system Digital assistant. If ChatGPT is not available as a Digital assistant on this device, screen-locked voice may not work.
- The service stops after a detection and does not automatically resume listening.
- The user must explicitly grant Display over other apps permission; without it the app falls back to a tap-to-open notification.
- CI build success is not evidence of direct Voice launch, lock-screen capture, or battery behavior.

## Release and test gate

Stage 3 release workflow: .github/workflows/release-1.3.0.yml. APKs are attached to immutable versioned GitHub Releases, not committed to source. Stage 3 is not complete until the custom “yo, Ai Overlord” model is integrated and physical-device acceptance results are recorded.
