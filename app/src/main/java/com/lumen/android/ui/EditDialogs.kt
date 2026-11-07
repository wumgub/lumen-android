package com.lumen.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lumen.android.R
import com.lumen.android.library.SongEdit
import com.lumen.android.library.fold
import com.lumen.android.model.Track

/**
 * Everything about one song in one place: title, artist, album and note. Edits
 * only this song, so giving one untagged file an artist leaves the other
 * "Unknown artist" songs alone. Typing an album suggests existing ones.
 */
@Composable
fun EditSongDialog(track: Track, albums: List<String>, onDismiss: () -> Unit, onSave: (SongEdit) -> Unit) {
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    var album by remember { mutableStateOf(track.album) }
    var note by remember { mutableStateOf(track.note) }
    val q = fold(album.trim())
    val suggestions = remember(albums, q) {
        if (q.isEmpty() || albums.any { it == album.trim() }) emptyList()
        else albums.filter { fold(it).contains(q) }.take(4)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = LumenBg2, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("Edit song", color = LumenFg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                Field("Title", title) { title = it }
                Field("Artist", artist, placeholder = UNKNOWN_ARTIST) { artist = it }
                Field("Album", album, placeholder = "No album") { album = it }
                suggestions.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth().clickable { album = name }.padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_albums), contentDescription = null, tint = LumenDim, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(name, color = LumenFg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    minLines = 2,
                    maxLines = 5,
                    placeholder = { Text("Anything you like. Search finds it.", color = LumenDim) },
                    colors = editorFieldColors(),
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel", color = LumenDim) }
                    Button(onClick = { onSave(SongEdit(title, artist, album, note)) }) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        placeholder = { if (placeholder != null) Text(placeholder, color = LumenDim) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = editorFieldColors(),
    )
}

/** The three ways to set an album's cover, as on desktop. */
@Composable
fun CoverDialog(
    album: String,
    hasOverride: Boolean,
    onDismiss: () -> Unit,
    onCustomImage: () -> Unit,
    onFromSong: () -> Unit,
    onReset: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = LumenBg2, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.width(300.dp).padding(vertical = 16.dp)) {
                Text(
                    "Album cover",
                    color = LumenFg,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(album, color = LumenDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(8.dp))
                MenuRow(R.drawable.ic_image, "Custom image…", onCustomImage)
                MenuRow(R.drawable.ic_songs, "Choose from a song…", onFromSong)
                if (hasOverride) MenuRow(R.drawable.ic_close, "Reset to default", onReset)
            }
        }
    }
}

@Composable
private fun MenuRow(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = LumenDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = LumenFg, fontSize = 14.sp)
    }
}

/** Search the library for a song, e.g. to borrow its art. */
@Composable
fun SongPickerDialog(tracks: List<Track>, title: String, onDismiss: () -> Unit, onPick: (Track) -> Unit) {
    var text by remember { mutableStateOf("") }
    val q = fold(text.trim())
    val matches = remember(tracks, q) {
        tracks.filter { it.artUri != null && (q.isEmpty() || matchesSearch(it, q)) }.take(100)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = LumenBg2, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.width(320.dp).padding(vertical = 16.dp)) {
                Text(title, color = LumenFg, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("Search songs", color = LumenDim) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = editorFieldColors(),
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(matches, key = { it.id }) { t ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(t) }.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(t.artUri, Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title, color = LumenFg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), color = LumenDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Search across title, artist, album and note, with [q] already folded. */
fun matchesSearch(t: Track, q: String): Boolean =
    fold(t.title).contains(q) || fold(t.artist).contains(q) || fold(t.album).contains(q) ||
        (t.note.isNotEmpty() && fold(t.note).contains(q))
