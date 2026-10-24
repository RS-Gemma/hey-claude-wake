# Android Wake Phrase Service

A native Kotlin Android app that listens for one built-in wake phrase locally and surfaces a configured action.

**Current phase:** Stage 2 — background-process resilience, version 1.1.0.  
**Target device:** Samsung Galaxy A15 5G (SM-A156U), Android 16.  
**Wake phrase:** “Hey Jarvis” (bundled openWakeWord model).  
**Action:** Show a notification that opens the official ChatGPT app when tapped.

## Stage 1 verification

The project owner confirmed on the physical target device that v1.0.2 detected “Hey Jarvis” at a 0.35 confidence threshold, displayed the action notification, and opened ChatGPT when the notification was tapped.

## Stage 2 change

Version 1.1.0 changes the foreground service's successful start mode from `START_NOT_STICKY` to `START_STICKY`. Android can therefore attempt to recreate the listener service if it reclaims the app process while the service is active. Startup failures and microphone preflight failures remain non-sticky, and the notification's Stop action still explicitly stops the service.

**This is not a guarantee of uninterrupted listening.** Android/Samsung may still stop or restrict the service due to force-stop, reboot, permission changes, resource pressure, or battery-management policy. The app does not auto-start on boot and does not bypass Android restrictions. Stage 2 must be tested on the target phone with the screen off, after backgrounding the app, and under Samsung battery-management settings.

## Build

Open this repository in Android Studio with a compatible Android SDK and JDK 17, or use the included Gradle wrapper:

    ./gradlew --no-daemon assembleDebug

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

Versioned test APKs are published in [GitHub Releases](https://github.com/pistolwhip-justin/Android-Wake-Phrase-Service/releases). APKs are not committed to the source tree.

## Permissions and privacy

- Microphone permission is required.
- Wake-word detection is intended to run locally using the bundled model; the app does not intentionally upload microphone audio.
- While listening, Android displays a foreground-service notification with a Stop action.
- Start listening while the app is visible. Android restricts starting microphone foreground services from the background.
- Detection posts a notification; tap it to open ChatGPT. It does not guarantee entry into ChatGPT Voice mode.
- No boot auto-start, cloud audio processing, analytics, accounts, ads, or paid features are included.

## Model licensing

The upstream openWakeWord Android library is Apache-2.0, but its bundled pretrained wake-word models are licensed separately under CC BY-NC-SA 4.0. This model is for non-commercial use; this project is not intended for commercial distribution. See `THIRD_PARTY_NOTICES.md`.

## Development protocols

This project follows the shared standards in [development-protocols-n-assets](https://github.com/pistolwhip-justin/development-protocols-n-assets). See `AGENTS.md`.
