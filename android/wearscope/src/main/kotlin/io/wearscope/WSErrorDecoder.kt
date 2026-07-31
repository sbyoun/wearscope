/*
 * DAT failure-mode catalog — attaches an "explanation" to error strings. Every entry confirmed on real hardware.
 * Android errors often arrive as UPPER_SNAKE (enum names), so both spellings are kept as needles.
 */
package io.wearscope

internal object WSErrorDecoder {

  fun explain(description: String): String? {
    val d = description.lowercase()
    return catalog.firstOrNull { (needle, _) -> d.contains(needle) }?.second
  }

  private val catalog: List<Pair<String, String>> = listOf(
      "no_eligible_device" to NO_ELIGIBLE,
      "noeligibledevice" to NO_ELIGIBLE,
      "superseded" to "A newer send superseded this one — not an error. Do not re-attach (causes capabilityAlreadyActive)",
      "capability_already_active" to "Duplicate start on the same capability — reuse the existing instance",
      "capabilityalreadyactive" to "Duplicate start on the same capability — reuse the existing instance",
      "device_disconnected" to "Glasses disconnected — drop the capability reference and re-attach",
      "devicedisconnected" to "Glasses disconnected — drop the capability reference and re-attach",
      "dat_app_on_the" to UPDATE_REQUIRED,
      "datapponthe" to UPDATE_REQUIRED,
      "no_device" to NO_DEVICE,
      "nodevice" to NO_DEVICE,
      "already_registered" to "Already registered — not an error. Usually caused by a duplicate registration attempt",
      "alreadyregistered" to "Already registered — not an error. Usually caused by a duplicate registration attempt",
      "meta_ai_not_installed" to "Meta AI app not installed — the registration deep link has no target",
      "registrationerror" to
          "Registration failed — check Meta AI sign-in, Developer Mode, and app connection state. Silent failures reported when Developer Mode is off",
  )

  private const val NO_ELIGIBLE =
      "No eligible device for the session — common transiently right after registration/connection. Retrying at 1.5 s intervals resolves most cases"

  private const val UPDATE_REQUIRED =
      "DAT component on the glasses does not match the SDK version — check for a glasses update in the " +
          "Meta AI app. Glasses auto-update overnight and can drift ahead of apps built on an older SDK"

  private const val NO_DEVICE =
      "No device for the permission/session. Check in order: ① manifest BT permissions (see preflight) " +
          "② the glasses are connected and worn right now ③ Developer Mode keeps only one app " +
          "registered — if another app registered, re-register this one (#85, intended behavior) ④ no firmware update is pending"
}
