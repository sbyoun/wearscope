/*
 * DAT failure-mode catalog — attaches an "explanation" to error strings.
 * This catalog is WearScope's domain moat: every entry was confirmed on real hardware.
 */

import Foundation

enum WSErrorDecoder {
  /// Returns a human-readable explanation for known patterns.
  static func explain(_ description: String) -> String? {
    let d = description.lowercased()
    for (needle, explanation) in catalog where d.contains(needle) {
      return explanation
    }
    return nil
  }

  /// (lowercased match string, explanation). First match from the top wins.
  private static let catalog: [(String, String)] = [
    ("noeligibledevice",
     "No eligible device for the session — common transiently right after registration/connection. " +
     "Retrying at 1.5 s intervals resolves most cases"),
    ("superseded",
     "A newer send superseded this one — not an error. Do not re-attach (causes capabilityAlreadyActive)"),
    ("capabilityalreadyactive",
     "Duplicate start on the same capability — reuse the existing instance"),
    ("devicedisconnected",
     "Glasses disconnected — drop the capability reference and re-attach"),
    ("datapponthe",  // datAppOnTheGlassesUpdateRequired
     "DAT component on the glasses does not match the SDK version — check for a glasses update in the " +
     "Meta AI app. Glasses auto-update overnight and can drift ahead of apps built on an older SDK"),
    ("-9802",
     "TLS negotiation failed — iOS rejects certificates without a SAN (CN=IP) regardless of delegate " +
     "acceptance. Check the server certificate"),
    ("securaeconnectionfailed", "TLS negotiation failed — check the server certificate (SAN present?)"),
    ("secureconnectionfailed", "TLS negotiation failed — check the server certificate (SAN present?)"),
    ("sandbox extension",
     "Known DAT log noise (discussion #195) — widely reported as having no functional impact"),
    ("permissionerror(rawvalue: 0)",  // PermissionError.noDevice — when reported as rawValue
     "No device for the permission request (noDevice). Check in order: ① Info.plist has " +
     "NSBluetoothAlwaysUsageDescription (without it BT discovery is silently blocked — device list stays " +
     "empty forever) ② the glasses are connected and worn right now ③ Developer Mode keeps only one app " +
     "registered — if another app registered, re-register this one (#85, intended behavior) " +
     "④ no firmware update is pending"),
    ("nodevice",
     "No device for the permission/session — check the glasses show as 'connected' in Meta AI and are " +
     "worn/out of the case. A common state: BT audio (A2DP) is attached but the glasses never appear as " +
     "a DAT device (discussion #201)"),
    ("permissionerror",
     "Glasses permission error — requires the Meta AI deep-link round trip. Check device connection " +
     "state and the app's link scheme"),
    ("alreadyregistered",
     "Already registered — not an error. Usually caused by double-tapping the register button"),
    ("metaainotinstalled",
     "Meta AI app not installed — the registration deep link has no target"),
    ("configurationinvalid",
     "MWDAT plist credentials (MetaAppID/ClientToken/bundle ID) mismatch — check the Dev Center app record"),
    ("registrationerror",
     "Registration failed — check Meta AI sign-in, Developer Mode, and app connection state. " +
     "Silent failures reported when Developer Mode is off"),
  ]
}
