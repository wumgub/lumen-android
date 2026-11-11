package com.lumen.android.playback

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.lumen.android.data.LoudnessDao
import com.lumen.android.data.LoudnessEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch

/**
 * Balance volume: measures every song's loudness once, in the background
 * (songs about to play first), and sets each song's gain as it starts.
 * Turning down uses the player's own volume, which changes right at the song
 * change; the rare boost goes through [GainProcessor], ramped so it can't
 * click. A song is measured again only if its file changes.
 */
class BalanceVolume(
    private val context: Context,
    private val player: Player,
    private val dao: LoudnessDao,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
) : Player.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    private val main = Handler(Looper.getMainLooper())
    private val gains = ConcurrentHashMap<Long, Double>()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lumen-balance").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private var measuring: Job? = null
    @Volatile private var first: List<Long> = emptyList()
    private var on = prefs.getBoolean(KEY_BALANCE, true)

    init {
        player.addListener(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        if (on) start()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        apply()
        val next = player.nextMediaItemIndex
        first = listOfNotNull(
            mediaItem?.mediaId?.toLongOrNull(),
            if (next >= 0) player.getMediaItemAt(next).mediaId.toLongOrNull() else null,
        )
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
        if (key != KEY_BALANCE) return
        on = p.getBoolean(KEY_BALANCE, true)
        apply()
        if (on) start()
    }

    fun release() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        player.removeListener(this)
        measuring?.cancel()
        worker.close()
    }

    /** Set the current song's gain. Main thread. */
    private fun apply() {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        val g = if (on && id != null) gains[id] ?: 1.0 else 1.0
        player.volume = min(g, 1.0).toFloat()
        Volume.boost = max(g, 1.0).toFloat()
    }

    private fun start() {
        if (measuring?.isActive == true) return
        measuring = scope.launch(worker) {
            val stored = dao.all().associateBy { it.mediaId }
            for (row in stored.values) {
                val lufs = row.lufs ?: continue
                gains[row.mediaId] = gainFor(lufs, row.peak ?: 1.0)
            }
            main.post { apply() }
            val todo = songs().filter { (id, key) -> stored[id]?.fileKey != key }.toMutableList()
            while (todo.isNotEmpty() && on) {
                val i = first.firstNotNullOfOrNull { f -> todo.indexOfFirst { it.first == f }.takeIf { it >= 0 } } ?: 0
                val (id, key) = todo.removeAt(i)
                val uri = ContentUris.withAppendedId(collection(), id)
                val l = measureLoudness(context, uri)
                dao.put(LoudnessEntity(id, l?.lufs, l?.peak, key))
                // Takes effect from the song's next start, never halfway through.
                if (l != null) gains[id] = gainFor(l.lufs, l.peak)
            }
        }
    }

    /** Every song with its size and modified time, as "size:modified". */
    private fun songs(): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        context.contentResolver.query(collection(), projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to "${c.getLong(1)}:${c.getLong(2)}")
        }
        return out
    }

    private fun collection() = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    companion object {
        const val KEY_BALANCE = "balanceVolume"
    }
}
