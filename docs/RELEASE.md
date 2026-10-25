# Build and release

## Versioning

- 1.0.0 — initial Stage 1 app.
- 1.0.1 — microphone diagnostics and threshold change.
- 1.0.2 — mel-frame overlap inference correction.
- 1.1.0 — Stage 2 background-process resilience.
- 1.2.0 — Stage 3 ChatGPT Voice handoff compatibility test.

Each version uses a matching `vX.Y.Z` tag and APK name `Android-Wake-Phrase-Service-X.Y.Z.apk`.

## Release policy

- APKs belong only in GitHub Releases, never in the source tree.
- Each version has a dedicated workflow file, e.g. `.github/workflows/release-1.2.0.yml`.
- The workflow builds the debug variant signed with Android's default debug key. This is suitable for personal sideloading/testing, not a production Play Store build.
- The workflow must fail safely if a release already exists but is incomplete; it must not overwrite an existing release or its assets.
- A complete existing release is treated as already published and is not replaced.

## Stage 3 release workflow

The `.github/workflows/release-1.2.0.yml` workflow builds the debug APK and publishes it to the `v1.2.0` GitHub Release. The app UI and notification explain the supported ChatGPT Start with Voice setting.

This is a compatibility experiment. It does not claim to force ChatGPT Voice mode or launch the app hands-free. The physical-device acceptance gate is documented in `docs/DEVELOPMENT_PLAN.md`.

## Manual build

Requirements: JDK 17 and Android SDK; the included wrapper uses Gradle 8.9.

    ./gradlew --no-daemon testDebugUnitTest assembleDebug

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Installation

Download the versioned APK from the [GitHub Releases page](https://github.com/pistolwhip-justin/Android-Wake-Phrase-Service/releases) to the phone. Allow installation from the browser/file manager used to download it, then review Android's warning and install only if you trust the release source. The debug build has the `.debug` application ID suffix, so it can coexist with the regular package rather than updating it.

## Verification record

A release record must state the commit SHA, CI result, APK filename, and whether physical-device tests have passed. Never describe an APK as built or tested until the corresponding evidence exists.

## Physical-device verification

- **Stage 1:** Owner confirmed that v1.0.2 detected “Hey Jarvis” at confidence threshold 0.35, showed the detection notification, and opened ChatGPT after the notification was tapped.
- **Stage 2:** Owner reports consistent true detections across screens including lock screen, one false positive, and no noticeable battery drain during testing. These are qualitative owner-reported observations.
- **Stage 3:** Pending owner test of ChatGPT's Start with Voice behavior on the Galaxy A15 5G (SM-A156U), Android 16. CI success alone does not verify Voice-mode activation.
