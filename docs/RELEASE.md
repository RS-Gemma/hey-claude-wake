# Build and release

## Versioning

- 1.0.0 — initial Stage 1 app.
- 1.0.1 — microphone diagnostics and threshold change.
- 1.0.2 — mel-frame overlap inference correction.
- 1.1.0 — Stage 2 background-process resilience.
- 1.2.0 — Stage 3 ChatGPT Start with Voice compatibility guidance.
- 1.3.0 — Stage 3 experimental direct ChatGPT Voice launch.

Each version uses a matching vX.Y.Z tag and APK name Android-Wake-Phrase-Service-X.Y.Z.apk.

## Release policy

- APKs belong only in GitHub Releases, never in the source tree.
- Each version has a dedicated workflow file, e.g. .github/workflows/release-1.3.0.yml.
- The workflow builds the debug variant signed with Android's default debug key. This is suitable for personal sideloading/testing, not a production Play Store build.
- The workflow must fail safely if a release already exists but is incomplete; it must not overwrite an existing release or its assets.
- A complete existing release is treated as already published and is not replaced.

## Stage 3 release

The v1.3.0 workflow builds the experimental direct Voice-launch prototype. It preserves the detector and threshold but stops listening after a wake detection to release the microphone to ChatGPT. If granted Display over other apps permission, it attempts to launch ChatGPT's currently observed internal Voice activity, then a best-effort Voice deeplink. The detection notification remains as a fallback.

This is not an official OpenAI integration. The internal activity is undocumented, and a successful Android activity launch call does not prove a Voice session began. The direct launch, lock-screen behavior, and microphone handoff require physical-device testing.

## Manual build

Requirements: JDK 17 and Android SDK; the included wrapper uses Gradle 8.9.

    ./gradlew --no-daemon testDebugUnitTest assembleDebug

APK output: app/build/outputs/apk/debug/app-debug.apk.

## Installation

Download the versioned APK from the [GitHub Releases page](https://github.com/pistolwhip-justin/Android-Wake-Phrase-Service/releases) to the phone. Allow installation from the browser/file manager used to download it, then review Android's warning and install only if you trust the release source. The debug build has the .debug application ID suffix, so it can coexist with the regular package rather than updating the regular package.

## Verification record

- **Stage 1:** Owner confirmed v1.0.2 detection at threshold 0.35, notification, and ChatGPT opening after tap.
- **Stage 2:** Owner reports consistent true detections across screens including lock screen, one false positive, and no noticeable battery drain during testing. These are qualitative owner-reported observations.
- **Stage 3 v1.2.0:** Start with Voice guidance; did not directly launch Voice.
- **Stage 3 v1.3.0:** CI and physical-device results pending. Do not claim Voice-mode activation until the owner verifies it on the Galaxy A15 5G (SM-A156U), Android 16.
