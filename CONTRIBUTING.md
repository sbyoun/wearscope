# Contributing to WearScope

Hosted backend, public baselines and failure rankings: https://gs.foldalpha.com

WearScope is a **vendor-neutral instrumentation layer for smart-glasses apps**:
a shared event model (`sessionState` · `stream` · `photo` · `audioRoute` · `thermal` ·
`error` · `metric` · `custom`) plus per-vendor adapters that auto-instrument each
platform's SDK. The most valuable contributions are **new vendor adapters** and
**failure-mode catalog entries** — both come from hardware access and field
debugging we can't do alone.

## Adapters wanted 🥽

We physically have Meta (DAT) hardware — the adapters below need contributors
with the devices:

| Platform | SDK to wrap | Status |
|---|---|---|
| Meta Wearables DAT | MWDATCore/MWDATCamera (iOS) · DAT Kotlin (Android) | ✅ shipped (`Sources/WearScopeDAT`, `android/wearscope-dat`) |
| **Android XR glasses** | **Jetpack Projected** (phone-companion — same shape as DAT) | 🙏 wanted — devices ship Fall 2026 |
| **Rokid** | CXR SDK (YodaOS companion/native) | 🙏 wanted |
| Sentry / Embrace forwarding | export events as breadcrumbs/custom events | optional extra, planned — the primary backend is the WearScope dashboard |

### What an adapter is

An adapter is a thin module that subscribes to a vendor SDK's observable surface
and emits WearScope events. Study `Sources/WearScopeDAT/WearScopeDAT.swift`
(~250 lines) — it is the reference shape:

1. **Environment snapshot** on start: `custom/env` (vendor SDK version, locale) and
   `custom/devices` (glasses model names — the key fleet-segmentation field).
2. **Preflight**: check the app's configuration for known *silent* failure setups
   (missing plist/manifest entries etc.) and emit `custom/preflight` / `error/preflight`.
3. **Lifecycle**: registration state → `sessionState/registration`; per-device
   connect/disconnect → `sessionState/link {device, state}`.
4. **Streams**: state transitions with dwell time (`stream/state {state, ms_in_prev}`),
   open latency (`stream/warm_open {ms}`), frame cadence (`metric/frames {fps, gap_p95_ms}`).
5. **Errors**: pass every vendor error through the failure catalog so known
   patterns get an `explain` attached.

Rules that keep WearScope trustworthy:

- **Metadata only.** Never capture audio, video, photo payloads, or transcripts.
  Attribute values are strings, capped at 500 chars.
- No new event *types* — extend with `name`/attrs within the existing eight types
  (servers and dashboards depend on the taxonomy).
- Zero required configuration beyond `WearScope.start()`.

## Failure-mode catalog entries 💡

[`docs/failure-modes.md`](docs/failure-modes.md) is the encyclopedia; the runtime
version lives in `WSErrorDecoder` (and the Kotlin equivalent). An entry needs:
**symptom → root cause → fix**, confirmed on physical hardware. PRs that add a
matcher + explanation + encyclopedia section together are ideal.

## Practicalities

- Discussions/issues first for anything structural; small fixes straight to PR.
- iOS: Swift 5.9+, iOS 17+, no dependencies. Android: see `android/README.md`.
- English for all user-visible strings and docs.
- By contributing you agree your work is MIT-licensed like the rest of the project.
