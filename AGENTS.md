# Integrating WearScope (instructions for coding agents)

You are adding observability to a smart-glasses app built on the Meta Wearables
Device Access Toolkit (DAT). Backend and dashboard: https://gs.foldalpha.com
(machine-readable summary: https://gs.foldalpha.com/llms.txt). Follow the platform section below verbatim; it is
three lines of setup plus one call per stream. Do not invent configuration —
there is none beyond what is listed here.

## iOS (Swift, iOS 17+)

Add the package: `https://github.com/sbyoun/wearscope` (product `WearScope`, and
`WearScopeDAT` if the app uses MWDAT).

```swift
import WearScope
import WearScopeDAT

// App start (e.g. in App.init or application(_:didFinishLaunchingWithOptions:))
WearScope.start()                                   // no key, no signup
WearScope.observeAudioRoutes()                      // glasses↔phone mic fallbacks

// Right after Wearables.configure()
WearScopeDAT.observe(wearables: Wearables.shared)   // registration, devices, link, preflight

// Wherever a camera stream is created, before/after start()
WearScopeDAT.observe(stream: stream)                // state transitions, errors, photo arrivals
WearScopeDAT.observeFrames(stream: stream)          // fps, inter-frame p95, measured resolution
```

Info.plist must contain `NSBluetoothAlwaysUsageDescription` (without it, DAT
device discovery fails silently) and `NSLocalNetworkUsageDescription` for
streaming. `WearScopeDAT` checks this at launch and reports what is missing.

## Android (Kotlin, minSdk 31)

Include the modules (sibling checkout, see `android/README.md`), then:

```kotlin
import io.wearscope.WearScope
import io.wearscope.dat.WearScopeDAT

// After runtime permissions are granted, next to Wearables.initialize(context)
WearScope.start(context)                    // no key, no signup
WearScope.observeAudioRoutes(context)
WearScopeDAT.observeWearables(context)      // registration, devices, link, preflight

// On the session and each stream you create
WearScopeDAT.observeSession(session)
WearScopeDAT.observeStream(stream)
WearScopeDAT.observeFrames(stream)

// capturePhoto returns a suspend Result — report the timing yourself
stream.capturePhoto().onSuccess { WearScopeDAT.trackPhoto(it, ms = elapsedMs) }
```

The manifest needs `BLUETOOTH_CONNECT` and `INTERNET`, plus the MWDAT
`APPLICATION_ID` / `CLIENT_TOKEN` meta-data. Preflight verifies these at launch.

## Adding your own events

```swift
WearScope.track(.stream, "warm_open", ["ms": "6100", "result": "ok"])
WearScope.trackError(error, context: "camera.session")   // known DAT errors get an explanation
let done = WearScope.measure(.photo, "capture"); done(["result": "ok"])
```

Event types are fixed: `sessionState` · `stream` · `photo` · `audioRoute` ·
`thermal` · `error` · `metric` · `custom`. Extend with `name` and attributes;
do not add new types (servers and dashboards depend on the taxonomy).

## Hard rules

- **Metadata only.** Never put audio, video, photo bytes, or transcripts into
  attributes. Values are strings, truncated at 500 characters.
- **Never let instrumentation break the app.** Every call is fire-and-forget; if
  the network or server is unavailable the SDK records to a local file instead.
- Do not add configuration files, environment variables, or build flags for
  WearScope. `start()` is the whole setup.

## Public data (no auth, useful before and after integrating)

```
GET https://gs.foldalpha.com/public/summary        # ecosystem totals
GET https://gs.foldalpha.com/public/leaderboard    # per-glasses warm_open / capture / fps
GET https://gs.foldalpha.com/public/failures       # failure-mode frequency + explanations
GET https://gs.foldalpha.com/public/stats/metrics?name=warm_open&by=glasses_model
GET https://gs.foldalpha.com/llms.txt              # this API, summarized for agents
```

Use these to answer "is 15 s to open a stream normal?" without guessing.

## Verifying the integration

1. Run the app. The console/logcat prints one `[WearScope]` line with either a
   dashboard URL or "local mode".
2. Local file (works offline): `WearScope.exportURL()` on iOS,
   `WearScope.exportFile()` on Android — newline-delimited JSON events.
3. If a dashboard URL was printed, open it: sessions appear within ~10 seconds
   (events batch every 50 events or 10 s).

A healthy first-launch timeline looks like: `custom/sdk_start` →
`custom/preflight {result: ok}` → `custom/env` → `sessionState/registration` →
`custom/devices` → `sessionState/link`. If `preflight` reports an error, fix that
first — it is the usual cause of "the glasses never show up".

## When something fails

Read [`docs/failure-modes.md`](docs/failure-modes.md) before debugging DAT
behavior; most "device never appears" / "photo never arrives" / "mic sounds
wrong" cases are documented there with root cause and fix.
