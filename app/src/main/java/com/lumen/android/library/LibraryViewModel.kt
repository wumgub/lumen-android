package com.lumen.android.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.android.data.AlbumCoverEntity
import com.lumen.android.data.AppDatabase
import com.lumen.android.data.OverrideEntity
import com.lumen.android.model.Track
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The library: what MediaStore has, with the user's edits laid over it. Edits
 * live in Room keyed by the stable MediaStore id, so they survive rescans and
 * never touch the files.
 */
class LibraryViewModel(private val app: Application) : AndroidViewModel(app) {

    private val scanner = MediaStoreScanner(app)
    private val edits = AppDatabase.get(app).editsDao()

    private val scanned = MutableStateFlow<List<Track>>(emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    val tracks: StateFlow<List<Track>> = combine(scanned, edits.overrides()) { list, overrides ->
        if (overrides.isEmpty()) {
            list
        } else {
            val byId = overrides.associateBy { it.trackId }
            list.map { t ->
                val o = byId[t.id] ?: return@map t
                t.copy(
                    title = o.title ?: t.title,
                    artist = o.artist ?: t.artist,
                    album = o.album ?: t.album,
                    note = o.note,
                )
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Album cover overrides by album name. */
    val covers: StateFlow<Map<String, AlbumCoverEntity>> = edits.covers()
        .map { rows -> rows.associateBy { it.album } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Scan once; safe to call repeatedly (ignored while a scan is in flight). */
    fun scan() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            val list = withContext(Dispatchers.IO) { scanner.scan() }
            scanned.value = list
            _scanning.value = false
        }
    }

    private suspend fun edit(ids: List<Long>, change: (OverrideEntity) -> OverrideEntity) {
        if (ids.isEmpty()) return
        val existing = edits.overridesFor(ids).associateBy { it.trackId }
        edits.upsertOverrides(ids.map { id -> change(existing[id] ?: OverrideEntity(trackId = id)) })
    }

    /**
     * Save the Edit song dialog. Only fields that changed are written, and only
     * for this one song: renaming its artist here doesn't touch other songs by
     * the same artist (that's what renaming the artist itself is for). A blank
     * title keeps the old one; a blank artist or album clears it.
     */
    fun editSong(track: Track, e: SongEdit) = viewModelScope.launch {
        val title = e.title.trim().ifEmpty { track.title }
        val artist = e.artist.trim()
        val album = e.album.trim()
        val note = e.note.trim()
        if (title == track.title && artist == track.artist && album == track.album && note == track.note) return@launch
        edit(listOf(track.id)) {
            it.copy(
                title = if (title != track.title) title else it.title,
                artist = if (artist != track.artist) artist else it.artist,
                album = if (album != track.album) album else it.album,
                note = note,
            )
        }
    }

    /** Rename an artist on every song by them. Renaming onto an existing name merges. */
    fun renameArtist(from: String, to: String) = viewModelScope.launch {
        val name = to.trim()
        if (name.isEmpty() || name == from) return@launch
        edit(tracks.value.filter { it.artist == from }.map { it.id }) { it.copy(artist = name) }
    }

    /**
     * Rename an album on every song on it. A custom cover moves with it, unless
     * the target album already has its own (a merge keeps the target's).
     */
    fun renameAlbum(from: String, to: String) = viewModelScope.launch {
        val name = to.trim()
        if (name.isEmpty() || name == from) return@launch
        edit(tracks.value.filter { it.album == from }.map { it.id }) { it.copy(album = name) }
        if (edits.cover(name) != null) edits.deleteCover(from) else edits.moveCover(from, name)
    }

    /** Put songs on an album by name (a new name makes a new album). Blank takes them off. */
    fun setAlbum(ids: List<Long>, album: String) = viewModelScope.launch {
        edit(ids) { it.copy(album = album.trim()) }
    }

    fun setCoverFromTrack(album: String, trackId: Long) = viewModelScope.launch {
        removeCoverFile(album)
        edits.upsertCover(AlbumCoverEntity(album = album, source = "track", trackId = trackId))
    }

    /** Copy a picked image into the app's storage and use it as the album's cover. */
    fun setCoverImage(album: String, image: Uri) = viewModelScope.launch {
        val file = withContext(Dispatchers.IO) {
            val dir = File(app.filesDir, "covers").apply { mkdirs() }
            val out = File(dir, UUID.randomUUID().toString())
            val ok = app.contentResolver.openInputStream(image)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
                true
            } ?: false
            if (ok) out else null
        } ?: return@launch
        removeCoverFile(album)
        edits.upsertCover(AlbumCoverEntity(album = album, source = "custom", path = file.absolutePath))
    }

    fun resetCover(album: String) = viewModelScope.launch {
        removeCoverFile(album)
        edits.deleteCover(album)
    }

    private suspend fun removeCoverFile(album: String) {
        val path = edits.cover(album)?.path ?: return
        withContext(Dispatchers.IO) { File(path).delete() }
    }
}

/** What the Edit song dialog hands back. */
data class SongEdit(val title: String, val artist: String, val album: String, val note: String)

/** Where an album's cover comes from: its override, else its first song's art. */
fun albumArt(albumTracks: List<Track>, cover: AlbumCoverEntity?, all: List<Track>): Uri? {
    if (cover != null) {
        if (cover.source == "custom" && cover.path != null) return Uri.fromFile(File(cover.path))
        if (cover.source == "track" && cover.trackId != null) {
            all.firstOrNull { it.id == cover.trackId }?.artUri?.let { return it }
        }
    }
    return albumTracks.sortedWith(compareBy({ it.discNo }, { it.trackNo })).firstOrNull { it.artUri != null }?.artUri
}
