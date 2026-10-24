# Build and release

## Versioning

Use semantic versioning. Stage 1 initial release is version 1.0.0, release tag v1.0.0, APK name Android-Wake-Phrase-Service-1.0.0.apk. Diagnostic update is version 1.0.1, release tag v1.0.1, APK name Android-Wake-Phrase-Service-1.0.1.apk. Inference fix is version 1.0.2, release tag v1.0.2, APK name Android-Wake-Phrase-Service-1.0.2.apk.

## Release policy

- APKs belong only in GitHub Releases, never in the source tree.
- Each version has a dedicated workflow file, e.g. .github/workflows/release-1.0.0.yml.
- The workflow builds a debug variant signed with Android's default debug key. This is suitable for personal sideloading/testing, not a production Play Store release.
- The workflow must fail safely if a release already exists but is incomplete; it must not overwrite an existing release or its assets.
- A complete existing release is treated as already published and is not replaced.

## Stage 1 release workflow

The version-specific workflow builds the APK and publishes it to the v1.0.0 GitHub Release. The repository needs GitHub Actions enabled and the workflow must have contents: write permission to create a release.

## Diagnostic update 1.0.1

The 1.0.1 workflow is `.github/workflows/release-1.0.1.yml`. It performs a microphone preflight, surfaces preflight/startup errors in the app, records detection confidence scores when a detection callback fires, and lowers the detector threshold from 0.5 to 0.35. The upstream library does not expose below-threshold scores through its public API; a lack of score therefore does not prove that audio is absent.

## Manual build

Requirements: JDK 17, Android SDK, Gradle 8.9.

    gradle assembleDebug

APK: app/build/outputs/apk/debug/app-debug.apk.

## Installation

Download Android-Wake-Phrase-Service-1.0.0.apk from the GitHub Release to the phone and allow installation from the browser/file manager used to download it. Review Android's warning and install only if you trust the release source.

## Verification record

A release record must state the commit SHA, CI result, APK filename, and whether physical-device tests have passed. Never describe an APK as built or tested until the corresponding evidence exists.


## Inference correction 1.0.2

The app now uses a local patched copy of the openWakeWord Android Kotlin pipeline. Each processing iteration analyzes 1,760 audio samples while the next iteration advances by 1,280 samples, leaving a 480-sample overlap. The mel spectrogram for that window includes three frames from the overlap; 1.0.2 drops those three frames before appending to the rolling mel buffer. This corrects a source-level timing/duplication defect. CI build success does not prove wake-word recognition; test on the Galaxy A15 before closing the device-verification gate.
