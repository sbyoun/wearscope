# WearScope for Android (Kotlin)

Same SDK, same wire format, same server as the Swift package — `sdk.name: wearscope-kotlin`.

## Modules

- **`wearscope`** — core: event recording, crash-safe local `jsonl`, batch upload (50 events / 10 s), local-only mode without an endpoint, failure-mode explanations, audio-route observation. Zero dependencies beyond the Android platform (`minSdk 31`).
- **`wearscope-dat`** — one-line auto-instrumentation for the Meta Wearables DAT (`mwdat` 0.8.0): preflight config validation, registration/devices/link tracking, session & stream state transitions with dwell times, fps + inter-frame p95 + measured resolution, photo capture metrics.

## Consume from a sibling checkout (current approach)

`settings.gradle.kts` of your app:

```kotlin
include(":wearscope", ":wearscope-dat")
project(":wearscope").projectDir = file("../wearscope/android/wearscope")
project(":wearscope-dat").projectDir = file("../wearscope/android/wearscope-dat")
```

`app/build.gradle.kts`:

```kotlin
implementation(project(":wearscope"))
implementation(project(":wearscope-dat"))
```

Your repositories must be able to resolve `com.meta.wearable:mwdat-*` (GitHub Packages credentials), which a DAT app already has.

## Quickstart

```kotlin
// After runtime permissions are granted, next to Wearables.initialize(context):
WearScope.start(context, apiKey = "ws_dev")                       // local-only mode
WearScope.start(context, apiKey = "gs_...", endpoint = "https://your-server")
WearScope.observeAudioRoutes(context)      // glasses↔phone mic fallback detection
WearScopeDAT.observeWearables(context)     // preflight + registration/devices/link

// Where you create sessions / streams:
WearScopeDAT.observeSession(session)
WearScopeDAT.observeStream(stream)
WearScopeDAT.observeFrames(stream)         // fps · gap p95 · measured resolution

// Android capturePhoto is a suspend Result — report it yourself:
stream.capturePhoto().onSuccess { photo ->
  WearScopeDAT.trackPhoto(photo, ms = elapsed)
}
```

Privacy: metadata only — no audio, frames, photos, or transcripts, enforced by design.

## Example app — WearBench (Android)

[`android/examples/wearbench`](examples/wearbench) mirrors the iOS example: registration
status + link state (with register/unregister), camera bench (stream-open time, fps, measured
frame resolution over MEDIUM/HIGH, still-capture latency over the BT pipeline), mic route
check (glasses SCO vs phone) + test tone, and an in-app timeline viewer (works offline).

```bash
cd android
# local.properties: sdk.dir, github_token(mwdat resolve), mwdat_application_id,
#                   mwdat_client_token, ws_api_key/ws_endpoint(optional — local mode without)
./gradlew :examples:wearbench:assembleDebug
```
