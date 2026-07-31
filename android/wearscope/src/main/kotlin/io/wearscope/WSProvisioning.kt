/*
 * Zero-config provisioning — no signup, no dashboard visit before you have data.
 *
 * On first launch without an API key, the SDK asks the server for an anonymous
 * project, stores the credentials locally, and logs the dashboard URL once.
 * The project can later be claimed into an account server-side; nothing is lost.
 */
package io.wearscope

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

internal data class WSCredentials(
    val apiKey: String,
    val projectId: String?,
    val dashboardUrl: String?,
)

internal object WSProvisioning {
  /** Default cloud endpoint — zero-config sends here unless the app overrides it. */
  const val DEFAULT_ENDPOINT = "https://gs.foldalpha.com"

  private const val PREFS = "wearscope"
  private const val KEY_API = "api_key"
  private const val KEY_PROJECT = "project_id"
  private const val KEY_DASHBOARD = "dashboard_url"

  fun cached(context: Context): WSCredentials? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val key = prefs.getString(KEY_API, null) ?: return null
    return WSCredentials(key, prefs.getString(KEY_PROJECT, null), prefs.getString(KEY_DASHBOARD, null))
  }

  private fun store(context: Context, creds: WSCredentials) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_API, creds.apiKey)
        .putString(KEY_PROJECT, creds.projectId)
        .putString(KEY_DASHBOARD, creds.dashboardUrl)
        .apply()
  }

  /**
   * Request an anonymous project. Returns null when the server is unreachable or
   * declines — the caller stays in local mode and retries on the next launch.
   * Must be called off the main thread.
   */
  fun provision(context: Context, endpoint: String, appName: String): WSCredentials? = try {
    val conn = URL("${endpoint.trimEnd('/')}/v1/projects/anonymous").openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    conn.connectTimeout = 15_000
    conn.readTimeout = 15_000
    conn.outputStream.use { it.write(JSONObject().put("name", appName).toString().toByteArray()) }
    val body = if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText() else null
    conn.disconnect()
    body?.let {
      val json = JSONObject(it)
      val key = json.optString("api_key")
      if (key.isBlank()) null
      else WSCredentials(
          key,
          json.optString("project_id").ifBlank { null },
          json.optString("dashboard_url").ifBlank { null },
      ).also { creds -> store(context, creds) }
    }
  } catch (_: Exception) {
    null
  }

  /** One-line log banner so a human (or the agent that wired this up) knows where to look. */
  fun announce(creds: WSCredentials, fresh: Boolean) {
    val where = creds.dashboardUrl ?: DEFAULT_ENDPOINT
    Log.i("WearScope", "${if (fresh) "Anonymous project created" else "Reporting"} → $where")
  }
}
