# WearScope

**Observability SDK for smart-glasses apps** built on the Meta Wearables Device Access Toolkit (DAT).

Session lifecycles, stream health, capture latency, audio-route fallbacks, and DAT errors — captured with one line of setup, explained with a catalog of known failure modes, and replayable as a session timeline.

> Status: **v0.2 developer preview.** API may change. Built by developers who spent months debugging DAT apps and wished this existed.
>
> 📖 **[The DAT Failure-Mode Encyclopedia](docs/failure-modes.md)** — field notes on every failure we hit on real hardware (`noEligibleDevice`, silent mic fallback, TLS -9802, …). Useful even without the SDK.

## Why

Glasses apps fail differently from phone apps. `noEligibleDevice` on a device that's right there. Streams that die on Wi-Fi join. A mic that silently falls back from the glasses to the phone. Generic APM tools don't know what any of that means — WearScope does, and tells you.

And platform telemetry only ever sees inside its own SDK. Your app's failure surface spans Bluetooth audio routes, networking, and your own code — half the entries in our [failure-mode encyclopedia](docs/failure-modes.md) live outside the DAT entirely. WearScope watches the whole app, and aims to do so on any vendor's glasses.

## Install

Swift Package Manager:

```swift
.package(url: "https://github.com/sbyoun/wearscope", from: "0.1.0")
```

Requires iOS 17+. The `WearScope` core library has zero dependencies; the optional `WearScopeDAT` auto-instrumentation product depends on the Meta Wearables DAT package (0.8.0).

**Android (Kotlin):** same SDK, same wire format — see [`android/`](android/README.md). Consumed as Gradle modules from a sibling checkout (`minSdk 31`, zero-dependency core).

## Quickstart

```swift
import WearScope
import WearScopeDAT

// At app launch. Without an endpoint, events are recorded to a local file only.
WearScope.start(apiKey: "ws_dev")
WearScope.observeAudioRoutes()          // detects silent glasses→phone mic fallbacks

// One line each — full auto-instrumentation:
WearScopeDAT.observe(wearables: Wearables.shared)  // registration, devices, env snapshot
WearScopeDAT.observe(stream: stream)               // state transitions, errors, photo arrivals
WearScopeDAT.observeFrames(stream: stream)         // fps + inter-frame p95 (opt-in)
```

That alone gives you a session timeline: registration → device appears → stream `starting` (join time measured) → `streaming` → errors with explanations → route changes. Add your own events where it helps:

```swift
// Events and metrics
WearScope.track(.stream, "state", ["state": "streaming", "resolution": "low"])
WearScope.track(.photo, "capture", ["ms": "401", "bytes": "48213"])

// Durations
let done = WearScope.measure(.stream, "warm_open")
// ... open stream ...
done(["result": "ok"])   // records elapsed ms

// Errors — known DAT failure modes get a human explanation attached automatically
WearScope.trackError(error, context: "camera.session")
```

## What it does

- **Local-first**: every event is appended to a crash-safe local `jsonl` file immediately; batches upload every 10 s / 50 events when an endpoint is configured. Upload failures are retried; nothing is lost on crash.
- **Error catalog**: `trackError` recognizes known DAT failure modes (`noEligibleDevice`, `Superseded`, firmware mismatches, TLS quirks, …) and attaches an explanation — the debugging note you'd otherwise find after hours in the discussions.
- **Privacy by design**: WearScope never collects payloads — no audio, video, photos, or transcripts. Metadata only, enforced at the API level (string attributes, 500-char cap).
- **Environment-first**: every session carries device model, OS, app build, SDK/DAT versions, locale, and glasses model — so a number is never just a number; it's comparable across the fleet ("your warm-open is 18 s; typical is 6 s").

## Event types

`sessionState` · `stream` · `photo` · `audioRoute` · `thermal` · `error` · `metric` · `custom`

## Example app — WearBench

[`Examples/WearBench`](Examples/WearBench) is the reference integration *and* a hardware
diagnostics tool: registration status, stream-open time / fps / inter-frame p95 by resolution,
still-capture latency, mic route check ("am I on the glasses mic or did it silently fall back
to the phone?"), speaker test tone — and an in-app viewer for the WearScope event timeline
(works fully offline, no server needed). The Android counterpart lives at [`android/examples/wearbench`](android/examples/wearbench).

```bash
cd Examples/WearBench
cp Secrets.xcconfig.example Secrets.xcconfig   # fill in your team + DAT credentials
xcodegen generate && open WearBench.xcodeproj
```

## Self-hosting an ingest server

The SDK posts batches to `POST {endpoint}/v1/ingest` with an `X-API-Key` header:

```jsonc
{
  "sdk":     { "name": "wearscope-swift", "version": "0.1.0" },
  "app":     { "bundleId": "…", "version": "0.1", "build": "…" },
  "device":  { "model": "iPhone17,3", "os": "iOS 26.1" },
  "session": { "id": "<uuid>", "startedAt": "<ISO8601>" },
  "events":  [ { "id": "<uuid>", "ts": "<ISO8601>", "type": "stream",
                 "name": "state", "attrs": { "state": "streaming" } } ]
}
```

Respond `2xx` to acknowledge. Deduplicate on `events[].id`, upsert sessions on `session.id`. A reference server and hosted dashboard are in development.

## Roadmap

- ~~v0.2 — DAT auto-instrumentation adapter, reference ingest server + session-timeline dashboard~~ ✅
- ~~v0.3 — benchmark/example app~~ ✅ · fleet baselines (per-device/firmware segments)
- ~~v0.4 — Android (Kotlin) SDK~~ ✅ (core + DAT adapter + preflight, dogfooded)
- v1.0 — hosted dashboard, self-serve projects

## License

MIT — see [LICENSE](LICENSE).

WearScope is an independent project and is not affiliated with, endorsed by, or sponsored by Meta Platforms, Inc.
