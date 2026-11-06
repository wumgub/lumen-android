package com.lumen.android.update

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val RELEASES_URL = "https://github.com/wumgub/lumen-android/releases/latest"
private const val API_URL = "https://api.github.com/repos/wumgub/lumen-android/releases/latest"

sealed interface UpdateResult {
    data class Ok(val latest: String, val upToDate: Boolean, val url: String) : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

/** Compare dotted versions numerically ("0.10.0" > "0.9.2"); missing parts count as 0. */
fun compareVersions(a: String, b: String): Int {
    val x = a.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
    val y = b.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    return 0
}

/** Ask GitHub for the newest release. Only runs when you tap the button. */
suspend fun checkForUpdate(current: String): UpdateResult = withContext(Dispatchers.IO) {
    try {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != 200) return@withContext UpdateResult.Failed("GitHub said ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val latest = json.optString("tag_name").removePrefix("v")
            if (latest.isBlank()) return@withContext UpdateResult.Failed("No releases found")
            UpdateResult.Ok(latest, compareVersions(current, latest) >= 0, json.optString("html_url", RELEASES_URL))
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        UpdateResult.Failed("Couldn't reach GitHub")
    }
}
