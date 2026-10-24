# Android Wake Phrase Service

A native Kotlin Android app that listens for one built-in wake phrase locally and surfaces a configured action.

**Current phase:** Stage 1 — single wake phrase, single action; diagnostic build 1.0.1.  
**Target device:** Samsung Galaxy A15 5G (SM-A156U), Android 16.  
**Wake phrase:** “Hey Jarvis” (bundled openWakeWord model).  
**Action:** Show a notification that opens the official ChatGPT app when tapped. Android restricts direct activity launches from background services; Stage 1 deliberately uses a user-visible notification rather than unreliable UI automation.

## Stage 1 status

See docs/DEVELOPMENT_PLAN.md for scope and acceptance gates and docs/RELEASE.md for release handling.

A source commit is not a verified APK. The Stage 1 release is complete only when GitHub Actions builds an installable APK and publishes it as a GitHub Release. Device-specific microphone, wake-word, notification, and battery behavior still requires physical-device testing.

## Diagnostics in 1.0.1

The app performs a short microphone preflight before starting the detector and displays the result in the app. It also displays a confidence score and timestamp when the detector reports a match. The detector threshold is 0.35 (the original build used 0.5). The upstream detector API only calls back after a score reaches the threshold, so this build does not display scores below threshold. Internal detector-thread errors may still only be visible in Android logs; the preflight helps distinguish common microphone failures from recognition failures.

## Build

Open this repository in Android Studio with a compatible Android SDK and JDK 17, or use Gradle 8.9:

    gradle assembleDebug

Debug APK output: app/build/outputs/apk/debug/app-debug.apk.

## Permissions and privacy

- Microphone permission is required.
- Wake-word detection is intended to run locally using the bundled model; the app does not intentionally upload microphone audio.
- While listening, Android displays a foreground-service notification.
- Start listening while the app is visible. Android restricts starting microphone foreground services from the background.
- Detection posts a notification; tap it to open ChatGPT. It does not guarantee entry into ChatGPT Voice mode.

## Model licensing

The upstream openWakeWord Android library is Apache-2.0, but its bundled pretrained wake-word models are licensed separately under CC BY-NC-SA 4.0. This Stage 1 build uses the “Hey Jarvis” model. That model license is non-commercial; review the model terms before commercial use or redistribution beyond the terms. See THIRD_PARTY_NOTICES.md.

## Development protocols

This project follows the shared standards in pistolwhip-justin/development-protocols-n-assets. See AGENTS.md.
