# The DAT Failure-Mode Encyclopedia

Field notes from building and debugging real apps on the Meta Wearables Device Access Toolkit (iOS, DAT 0.7–0.8). Every entry below was hit on physical hardware. WearScope's `trackError` recognizes these patterns and attaches the explanation automatically.

Corrections and new entries welcome — PRs appreciated.

---

## Sessions & registration

### `noEligibleDevice` when the device is right there
**Symptom:** `createSession` / capability attach fails with `noEligibleDevice`, yet the glasses are connected and visible in Meta AI.
**Cause:** Transient — device surfacing lags registration/connection events. Common right after app registration, reconnect, or capability start.
**Fix:** Retry with ~1.5 s spacing (5 attempts covers almost every case). Follow the official sample's `DeviceSessionManager` pattern: await the session **state stream** instead of polling ad hoc. Hand-rolled polling is the usual root cause of "it never connects."

### `registered`, but `devices` is always empty (and permissions fail with `noDevice`)
**Symptom:** Registration reports `registered`, the app appears in Meta AI's App Connections, yet `Wearables.shared.devices` stays empty and `requestPermission(.camera)` throws `PermissionError.noDevice`.
**Cause we hit:** Missing **`NSBluetoothAlwaysUsageDescription`** in Info.plist. DAT discovers the glasses over Bluetooth; without that usage string iOS blocks CoreBluetooth **silently** — no crash, no error, just an eternally empty device list. Diff your Info.plist against the CameraAccess sample when this happens (also check `UIBackgroundModes: bluetooth-central/peripheral` if you stream in background).
**Also check:** the glasses are actually connected right now (if your app's audio route shows phone `Speaker` instead of the glasses' A2DP output, they're off/in the case), Developer Mode is on, and no firmware update is pending.

### Registering app B silently kicks app A (one-dev-app slot)
**Symptom:** Your previously working app stops seeing devices after you registered a different app of yours.
**Cause:** Intended behavior — in Developer Mode **only one third-party app stays registered at a time**; registering a new app automatically unregisters the previous one (confirmed by Meta staff, discussion #85).
**Fix:** Re-register whichever app you're about to test. When juggling multiple apps in development, expect to re-run the registration round-trip on every switch.

### Registration stuck at `registering` / no permission prompt
**Symptom:** `startRegistration()` deeplinks to Meta AI but nothing happens, or returns without error and state never reaches `registered`.
**Causes seen in the wild:** Developer Mode not enabled on the glasses (fails **silently**); Meta AI signed into a different account; the glasses not finished pairing. See discussions #128, #130, #249.
**Fix:** Verify Developer Mode in Meta AI → device settings first. There is no error surface for this today — instrument registration state transitions so you can see where it stalls.

### `datAppOnTheGlassesUpdateRequired` with "matching" firmware
**Symptom:** Sessions refused with this error even though the Meta AI app shows no pending update.
**Cause:** The glasses-side DAT component updates on its own schedule. It can update overnight and outrun the SDK version your app pins — "it worked yesterday" is the signature.
**Fix:** Check Meta AI for a glasses update; power-cycle the glasses; if your SDK is behind the latest release, upgrade. (Discussion #218.)

## Display / HUD

### `Superseded` send errors
**Symptom:** `display.send(...)` throws `Superseded`.
**Cause:** A newer `send` replaced this one. **It is not a failure.**
**Fix:** Ignore it. Do **not** tear down and re-attach the capability — that produces `capabilityAlreadyActive`. Serialize sends (one in flight + a dirty flag) and the error disappears entirely.

### HUD attach dies with `deviceDisconnected` right after `addDisplay()`
**Symptom:** Display attaches then immediately reports `deviceDisconnected`.
**Cause:** `capability.start()` was never called, or you sent before the state reached `.started`.
**Fix:** Follow the DisplayAccess sample exactly: `addDisplay()` → subscribe `statePublisher` → `start()` → wait for `.started` → only then `send`.

## Camera & streaming

### `capturePhoto` returns `true` but no photo
**Symptom:** `capturePhoto(format:)` succeeds, no image anywhere.
**Cause:** The return value only means *request accepted*. The photo arrives asynchronously on `photoDataPublisher` — and capture only works while a stream is open.
**Fix:** Subscribe to `photoDataPublisher` before capturing; treat 10–15 s without arrival as a retry.

### First stream open takes 5–20 seconds (iOS)
**Symptom:** Long "starting" phase on the first stream of a session; instant when phone-camera streaming elsewhere.
**Cause:** iOS DAT streams transport over glasses Wi-Fi (SoftAP). Opening a stream = joining that Wi-Fi network. Closing = leaving. Every reopen pays the join again.
**Fix:** The warm-stream pattern: open a LOW-resolution stream once per session, keep it alive without consuming frames, capture stills on demand (~0.4 s over Wi-Fi), release at session end.

### Streaming dies when the screen locks
**Symptom:** Stream stops shortly after the phone display sleeps.
**Fix:** Keep the screen awake during sessions (`isIdleTimerDisabled = true`), restore on session end.

### Frame cadence jitter at "constant" fps
**Symptom:** 30 fps configured, but inter-frame gaps show p95 ≈ 86 ms with spikes to 600+ ms (discussion #199).
**Cause:** Inherent to the BT/Wi-Fi link — plan for it.
**Fix:** Don't build frame-timing-sensitive logic on wall-clock assumptions. Measure your actual cadence (`WearScopeDAT.observeFrames`) before tuning anything else.

## Audio

### The mic silently falls back from glasses to phone
**Symptom:** Speech recognition quality tanks; user is heard from across the room or not at all. No error anywhere.
**Cause:** Glasses audio is standard Bluetooth HFP, not DAT. If the HFP route isn't granted/held, iOS quietly routes input to the phone mic (discussion #250).
**Fix:** Verify the active input port after every route change — glasses present as `BluetoothHFP`. `WearScope.observeAudioRoutes()` records every change with port names so fallbacks are visible in the timeline.

### Mic initializes at 0 Hz after route churn
**Symptom:** `AVAudioEngine` input format reports 0 Hz / 0 channels; deactivating and reactivating the session doesn't help.
**Fix:** Recreate the entire `AVAudioEngine`. Session-level resets do not clear this state.

### Glasses show a "call" screen when your app starts audio
**Symptom:** Starting an HFP session makes the glasses behave like a phone call is active.
**Cause:** Glasses firmware reacts to SCO channel setup (and `voiceChat` mode makes it worse).
**Fix:** Use audio mode `.default`, and keep the SCO/audio session **alive across conversation stops** — tear down only your upstream connection (e.g. the model WebSocket). Repeated SCO open/close is what re-triggers the call UI.

### Your assistant answers itself (echo loop)
**Symptom:** The model hears its own TTS through the glasses mic and responds to it.
**Fix:** Mute your mic capture while the model speaks, plus a tail (~0.8 s) for room reverb. This forfeits barge-in; on this hardware that trade is worth it.

## Networking

### TLS fails with `-9802` while Chrome loads the same URL fine
**Symptom:** `NSURLErrorSecureConnectionFailed` (-9802) from URLSession; browsers connect happily. Custom trust delegates don't help.
**Cause:** Server certificate without a SAN (e.g. CN=IP). iOS ATS rejects it below the layer your delegate sees.
**Fix:** Fix the server certificate (domain + SAN, e.g. Let's Encrypt). No client-side workaround is worth shipping.

## Log noise (safe to ignore)

- `unable to make sandbox extension` — known sample/SDK log noise, no functional impact reported (discussion #195).
