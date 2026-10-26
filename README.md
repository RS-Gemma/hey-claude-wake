# Hey Claude (wake phrase → default assistant)

Fork of [Android Wake Phrase Service](https://github.com/pistolwhip-justin/Android-Wake-Phrase-Service), retargeted to open the system's default digital assistant (Claude) on a Samsung Galaxy S25 Ultra.

**Status:** test build. Wake phrase is the bundled openWakeWord “Hey Jarvis”. The launch method that lands in Claude's voice mode is not yet known; the app offers several methods to test from its main screen (ACTION_ASSIST, ACTION_VOICE_ASSIST, ACTION_VOICE_COMMAND, ACTION_ASSIST sent to Claude, plain Claude launch) and a diagnostics panel. None is verified on a device yet.

**Package ID:** `it.aicdp.heyclaude`. **Builds:** every push to `main` runs `.github/workflows/build.yml` and publishes a debug-signed APK as a GitHub pre-release.

---

## Original README (upstream, partly outdated)

# Android Wake Phrase Service

A native Kotlin Android app that listens for one built-in wake phrase locally and attempts to launch ChatGPT Voice.

**Current phase:** Stage 3 — experimental direct Voice launch, version 1.3.0.  
**Target device:** Samsung Galaxy A15 5G (SM-A156U), Android 16.  
**Wake phrase:** “Hey Jarvis” (bundled openWakeWord model).  
**Action:** On detection, release this app's microphone, attempt to launch ChatGPT Voice, and leave a notification as a fallback.

## Verification history

- **v1.0.2 / Stage 1:** The owner confirmed “Hey Jarvis” detection at threshold 0.35, a notification, and opening ChatGPT when tapped.
- **v1.1.0 / Stage 2:** The owner reports true detections consistently triggered notifications across screens including the lock screen, with one false positive and no noticeable battery drain. These are qualitative owner-reported observations.
- **v1.2.0:** Added instructions for ChatGPT's Start with Voice setting; this did not provide direct hands-free launch.
- **v1.3.0 / Stage 3:** Experimental attempt to launch ChatGPT's internal Voice activity directly, based on the publicly documented approach used by the open-source GPTWake project. The activity is undocumented by OpenAI and can change without notice. Physical-device behavior on the Galaxy A15 is not yet verified.

## Stage 3 setup and limitations

1. Install the official ChatGPT Android app, sign in, and grant ChatGPT microphone permission.
2. In this app, use **Enable direct Voice launch** and grant **Display over other apps** permission. This is a sensitive Android permission; the app uses it only to attempt the wake-triggered activity launch.
3. For Voice capture while the screen is locked, try setting ChatGPT as the system **Digital assistant** under Android Settings → Apps → Default apps → Digital assistant app. If ChatGPT is not offered as a choice, this route may not work on this device.
4. In ChatGPT Settings → Voice, enable **Background conversations** if available.
5. Start listening from this app while it is visible. Say “Hey Jarvis” and observe whether ChatGPT Voice starts automatically.

On detection, this app stops its listener and releases its microphone before attempting the ChatGPT launch. This is necessary to avoid competing for the microphone. **Listening does not automatically resume after the ChatGPT conversation**; restart it manually. If direct launch fails, the detection notification remains available as a normal ChatGPT launch fallback.

The direct component (com.openai.voice.assistant.AssistantActivity) is not a public API. A fallback link is attempted if the component is unavailable, but neither path guarantees that Voice starts. Android/Samsung restrictions and ChatGPT app changes may prevent direct launch. The app does not use root, device-admin privileges, or Accessibility automation.

## Stage 2 background resilience

Version 1.1.0 changed the foreground service's successful start mode to START_STICKY, allowing Android to attempt service recreation after process reclamation. It does not guarantee uninterrupted listening and does not restart after reboot or user force-stop.

## Build

Use JDK 17 and a compatible Android SDK:

    ./gradlew --no-daemon testDebugUnitTest assembleDebug

Debug APK output: app/build/outputs/apk/debug/app-debug.apk.

Versioned test APKs are published in [GitHub Releases](https://github.com/pistolwhip-justin/Android-Wake-Phrase-Service/releases). APKs are not committed to the source tree.

## Permissions and privacy

- Microphone permission is required for local wake-word detection.
- Display over other apps permission is optional but required for the experimental direct-launch attempt.
- Wake-word detection runs on-device; the app does not intentionally upload microphone audio.
- While listening, Android displays a persistent foreground-service notification with a Stop action.
- Detection releases the microphone and stops listening before attempting to launch ChatGPT.
- No boot auto-start, analytics, accounts, ads, or paid features are included.

## Model licensing

The upstream openWakeWord Android library is Apache-2.0, but its bundled pretrained wake-word models are licensed separately under CC BY-NC-SA 4.0. This model is for non-commercial use; this project is not intended for commercial distribution. See THIRD_PARTY_NOTICES.md.

## Development protocols

This project follows the shared standards in [development-protocols-n-assets](https://github.com/pistolwhip-justin/development-protocols-n-assets). See AGENTS.md.
