package com.lumen.android.migration

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lumen.android.data.AlbumCoverEntity
import com.lumen.android.data.AppDatabase
import com.lumen.android.data.OverrideEntity
import com.lumen.android.data.PlayEntity
import com.lumen.android.data.PlaylistEntity
import com.lumen.android.data.PlaylistTrackEntity
import com.lumen.android.playback.ListenTracker
import com.lumen.android.playback.PlaybackService
import com.lumen.android.theme.ThemeController
import com.lumen.android.theme.parseTokens
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Brings data over from the WebView version of Lumen (up to v0.3.2), once.
 *
 * That app was the same package, so its WebView storage is still here after
 * the update: IndexedDB `lumen` and localStorage, both under the origin
 * `https://localhost` that Capacitor served it from. A hidden WebView loads a
 * page from that same origin, reads everything out, and hands it over as JSON.
 * Tracks were keyed by MediaStore id there too, so playlists, plays and edits
 * line up with this app's library directly.
 */
object WebViewImport {
    private const val TAG = "LumenImport"
    private const val KEY_DONE = "webviewImportDone"
    private const val KEY_TRIES = "webviewImportTries"
    private const val ORIGIN = "https://localhost"
    private const val PAGE = "$ORIGIN/__lumen_import.html"

    /** One import per process, even if the activity is recreated meanwhile. */
    @Volatile private var started = false

    fun runIfNeeded(context: Context, themeController: ThemeController) {
        val prefs = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
        if (started || prefs.getBoolean(KEY_DONE, false)) return
        started = true
        val tries = prefs.getInt(KEY_TRIES, 0)
        if (tries >= 3) return
        prefs.edit().putInt(KEY_TRIES, tries + 1).apply()
        try {
            start(context.applicationContext, themeController)
        } catch (e: Exception) {
            // No WebView provider, or it refused to start. Nothing to import then.
            Log.w(TAG, "import skipped", e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(context: Context, themeController: ThemeController) {
        val main = Handler(Looper.getMainLooper())
        val web = WebView(context)
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            main.post {
                web.stopLoading()
                web.destroy()
            }
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun done(json: String) {
                finish()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        apply(context, themeController, JSONObject(json))
                    } catch (e: Exception) {
                        Log.w(TAG, "import failed", e)
                    }
                }
            }
        }, "LumenImport")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                // Serve our one page; nothing ever goes to the network.
                val body = if (request.url.toString() == PAGE) EXPORT_PAGE else ""
                return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(body.toByteArray()))
            }
        }
        web.loadUrl(PAGE)
        main.postDelayed({ finish() }, 20_000)
    }

    private suspend fun apply(context: Context, themeController: ThemeController, data: JSONObject) {
        val prefs = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)
        val db = AppDatabase.get(context)
        val playlistDao = db.playlistDao()
        val edits = db.editsDao()
        val stats = db.statsDao()

        val stores = data.optJSONObject("stores") ?: JSONObject()
        val meta = HashMap<String, Any?>()
        stores.optJSONArray("meta")?.forEachObject { meta[it.optString("key")] = it.opt("value") }

        // Only fill an empty app, so a second run can't duplicate anything.
        val fresh = playlistDao.nextPlaylistPosition() == 0
        if (fresh) {
            val order = (meta["playlistOrder"] as? JSONArray)?.let { a -> (0 until a.length()).map { a.optLong(it) } } ?: emptyList()
            val playlists = stores.optJSONArray("playlists").objects()
                .sortedBy { p -> order.indexOf(p.optLong("id")).let { if (it < 0) Int.MAX_VALUE else it } }
            val ids = HashMap<Long, Long>()
            playlists.forEachIndexed { i, p ->
                ids[p.optLong("id")] = playlistDao.insertPlaylist(PlaylistEntity(name = p.optString("name", "Playlist"), position = i))
            }
            stores.optJSONArray("playlistTracks").objects()
                .sortedBy { it.optInt("pos") }
                .forEach { r ->
                    val pl = ids[r.optLong("playlistId")] ?: return@forEach
                    playlistDao.addTrack(PlaylistTrackEntity(playlistId = pl, trackId = r.optLong("trackId"), position = r.optInt("pos")))
                }

            val plays = stores.optJSONArray("plays").objects().map {
                PlayEntity(
                    trackId = it.optLong("trackId"),
                    playedAt = it.optLong("playedAt"),
                    seconds = it.optLong("seconds"),
                    counted = it.opt("counted").let { c -> c == true || (c as? Number)?.toInt() == 1 },
                )
            }
            if (plays.isNotEmpty()) stats.insertAll(plays)

            (meta["overrides"] as? JSONObject)?.let { ov ->
                val rows = ov.keys().asSequence().mapNotNull { k ->
                    val id = k.toLongOrNull() ?: return@mapNotNull null
                    val o = ov.optJSONObject(k) ?: return@mapNotNull null
                    OverrideEntity(
                        trackId = id,
                        title = o.optStringOrNull("title"),
                        artist = o.optStringOrNull("artist"),
                        album = o.optStringOrNull("album"),
                        note = o.optString("note", ""),
                    )
                }.toList()
                if (rows.isNotEmpty()) edits.upsertOverrides(rows)
            }

            stores.optJSONArray("albumCovers").objects().forEach { c ->
                val album = c.optString("album")
                if (album.isEmpty()) return@forEach
                when (c.optString("source")) {
                    "track" -> if (c.has("trackId")) edits.upsertCover(AlbumCoverEntity(album, "track", trackId = c.optLong("trackId")))
                    "custom" -> {
                        val b64 = c.optString("blob")
                        if (b64.isNotEmpty()) {
                            val dir = File(context.filesDir, "covers").apply { mkdirs() }
                            val file = File(dir, UUID.randomUUID().toString())
                            file.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                            edits.upsertCover(AlbumCoverEntity(album, "custom", path = file.absolutePath))
                        }
                    }
                }
            }
        }

        // Settings and the bits kept in localStorage.
        val editor = prefs.edit()
        (meta["settings"] as? JSONObject)?.let { s ->
            if (s.has("statsEnabled")) editor.putBoolean(ListenTracker.KEY_STATS_ENABLED, s.optBoolean("statsEnabled", true))
        }
        val local = data.optJSONObject("localStorage") ?: JSONObject()
        local.optStringOrNull("lumen.volume")?.toFloatOrNull()?.let { editor.putFloat(PlaybackService.KEY_VOLUME, it.coerceIn(0f, 1.5f)) }
        local.optStringOrNull("lumen.viz")?.let { editor.putString("viz", it) }
        local.optStringOrNull("lumen.viz-fps")?.toIntOrNull()?.let { editor.putInt("vizFps", if (it == 30 || it == 15) it else 60) }
        local.optStringOrNull("lumen.viz-off")?.let { editor.putBoolean("showViz", it != "1") }
        editor.putBoolean(KEY_DONE, true).apply()

        val theme = local.optStringOrNull("lumen.theme")
        val custom = local.optStringOrNull("lumen.custom")?.let { parseTokens(it) }
        Handler(Looper.getMainLooper()).post {
            custom?.let { themeController.replaceCustom(it) }
            theme?.let { themeController.setTheme(it) }
        }
        Log.i(TAG, "imported the WebView app's data")
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private inline fun JSONArray.forEachObject(f: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(f)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    /** Runs in the hidden WebView: dump IndexedDB `lumen` and localStorage as JSON. */
    private val EXPORT_PAGE = """
        <!doctype html><meta charset="utf-8"><script>
        (async () => {
          const out = { stores: {}, localStorage: {} };
          try {
            for (let i = 0; i < localStorage.length; i++) {
              const k = localStorage.key(i);
              if (k && k.startsWith('lumen.')) out.localStorage[k] = localStorage.getItem(k);
            }
          } catch (e) {}
          const blobToB64 = (b) => new Promise((res) => {
            const r = new FileReader();
            r.onload = () => res(String(r.result).split(',')[1] || '');
            r.onerror = () => res('');
            r.readAsDataURL(b);
          });
          try {
            const db = await new Promise((res, rej) => {
              const req = indexedDB.open('lumen');
              req.onsuccess = () => res(req.result);
              req.onerror = () => rej(req.error);
            });
            const wanted = ['playlists', 'playlistTracks', 'albumCovers', 'plays', 'meta'];
            for (const name of wanted) {
              if (!db.objectStoreNames.contains(name)) continue;
              const rows = await new Promise((res, rej) => {
                const req = db.transaction(name, 'readonly').objectStore(name).getAll();
                req.onsuccess = () => res(req.result);
                req.onerror = () => rej(req.error);
              });
              if (name === 'albumCovers') {
                for (const r of rows) if (r.blob instanceof Blob) r.blob = await blobToB64(r.blob);
              }
              out.stores[name] = rows;
            }
            db.close();
          } catch (e) {}
          LumenImport.done(JSON.stringify(out));
        })();
        </script>
    """.trimIndent()
}
