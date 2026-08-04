# WearScope

**Observability for smart-glasses apps.** Three lines, no signup: your session
timeline appears in a browser, and your numbers sit next to everyone else's.

**Dashboard & public data → [gs.foldalpha.com](https://gs.foldalpha.com)**

```swift
WearScope.start()                                   // no key, no account
WearScope.observeAudioRoutes()
WearScopeDAT.observe(wearables: Wearables.shared)   // → prints your dashboard URL
```

Session lifecycles, stream health, capture latency, audio-route fallbacks, and DAT
errors — captured automatically, explained with a catalog of known failure modes,
replayable as a timeline.

> **Using a coding agent?** Point it at [`AGENTS.md`](AGENTS.md) — copy-paste
> integration steps, the event taxonomy, hard rules, and how to verify it worked.
>
> 📖 **[The DAT Failure-Mode Encyclopedia](docs/failure-modes.md)** — field notes on
> every failure we hit on real hardware (`noEligibleDevice`, silent mic fallback,
> TLS -9802, …). Useful even without the SDK.

### What you get

- **Your timeline, instantly.** First launch provisions an anonymous project and
  prints a dashboard URL. No account, no project setup, nothing to configure.
  Claim it into an account later, self-host, or stay local-only — nothing is locked in.
- **Everyone's numbers, in the open.** Fleet baselines are public: how long a warm
  stream open *usually* takes, capture latency by phone and glasses model, fps and
  jitter across firmware versions. A number means nothing alone; ours come with a
  distribution to sit in.
- **Explanations, not just events.** Known DAT failures arrive with root cause and
  fix attached, from a catalog built on real hardware.

> Status: **v0.5 developer preview.** iOS + Android SDKs dogfooded on real hardware;
> the hosted cloud is live. If the server is ever unreachable the SDK records to a
> local file and retries next launch, so integration never blocks your app.

### See the data before you install anything

- **[Explore](https://gs.foldalpha.com/explore)** — the ecosystem right now
- **[Leaderboard](https://gs.foldalpha.com/public/leaderboard)** — warm-open, capture
  latency and fps per glasses model · **[Failure ranking](https://gs.foldalpha.com/public/failures)** —
  what actually breaks, how often, and why
- **[Baselines](https://gs.foldalpha.com/public/stats/metrics?name=warm_open)** —
  `?name=<metric>&by=glasses_model|device_model|os_version|dat_version`

All public, no auth. Send data and you also get "your number vs the fleet".

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

// At app launch. No API key, no signup: the first run provisions an anonymous
// project and logs your dashboard URL. (Pass apiKey:/endpoint: to use your own.)
WearScope.start()
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

## What the numbers look like

Measured on one pair of Ray-Ban Meta glasses, same SDK, two phones (our own
dogfooding — the kind of comparison the public baselines make routine):

| | iOS (Wi-Fi transport) | Android (BT transport) |
|---|---|---|
| Still capture | **0.5–0.7 s** | **4.5–5.7 s** |
| Stream open | 1–3 s (after device wait) | 1–3 s |
| Streaming fps | 24 | 13–14 |
| Frame resolution | 360×640 (low) | 504×896 (medium) |

Same hardware, ~8× difference in capture latency depending on which phone is
paired. That is the sort of thing nobody can tell you from a single device.

## Event types

`sessionState` · `stream` · `photo` · `audioRoute` · `thermal` · `error` · `metric` · `custom`

Beyond recording what happened, the adapter flags states that contradict each other —
a stream stuck in a transitional state while its session reports healthy, or frames
still arriving after a stream stopped (the glasses camera was never released, so the
next open hangs). Those are the shapes behind most "it just never starts" reports.

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
- v0.5 — zero-config provisioning ✅ · public fleet baselines & benchmark leaderboard (in progress)
- v1.0 — hosted dashboard, self-serve projects

## License

MIT — see [LICENSE](LICENSE).

WearScope is an independent project and is not affiliated with, endorsed by, or sponsored by Meta Platforms, Inc.
