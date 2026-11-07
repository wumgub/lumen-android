@file:OptIn(ExperimentalMaterial3Api::class)

package com.lumen.android.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumen.android.R
import com.lumen.android.data.AppDatabase
import com.lumen.android.data.PlaylistViewModel
import com.lumen.android.data.PlaylistWithCount
import com.lumen.android.library.LibraryViewModel
import com.lumen.android.library.albumArt
import com.lumen.android.library.fold
import com.lumen.android.model.Track
import com.lumen.android.playback.ListenTracker
import com.lumen.android.playback.PlayerActions
import com.lumen.android.playback.PlayerState
import com.lumen.android.playback.Volume
import com.lumen.android.theme.EDITABLE_TOKENS
import com.lumen.android.theme.START_FROM
import com.lumen.android.theme.ThemeController
import com.lumen.android.theme.exportTokens
import com.lumen.android.theme.parseTokens
import com.lumen.android.update.UpdateResult
import com.lumen.android.update.checkForUpdate
import com.lumen.android.viz.Visualizer
import com.lumen.android.viz.VizKind
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Detail {
    data class Artist(val name: String) : Detail
    data class Album(val name: String) : Detail
    data class Playlist(val id: Long, val name: String) : Detail
}

private data class ArtistItem(val name: String, val count: Int)
private data class AlbumItem(val name: String, val artist: String, val artUri: Uri?, val count: Int)

internal const val UNKNOWN_ARTIST = "Unknown artist"

/** What a song's ⋮ menu can do. */
private class SongMenu(
    val playNext: (Track) -> Unit,
    val addToQueue: (Track) -> Unit,
    val addToPlaylist: (Track) -> Unit,
    val edit: (Track) -> Unit,
    val removeFromAlbum: (Track) -> Unit,
    val goToArtist: (Track) -> Unit,
    val goToAlbum: (Track) -> Unit,
    val removeFromPlaylist: ((Track) -> Unit)? = null,
)

private enum class SongSort(val label: String) {
    Title("Title"), Artist("Artist"), Album("Album"), Added("Date added"), Year("Year"), Duration("Duration")
}

private data class SortState(val by: SongSort, val desc: Boolean)

private fun sortTracks(list: List<Track>, s: SortState): List<Track> {
    val ci = String.CASE_INSENSITIVE_ORDER
    val primary: Comparator<Track> = when (s.by) {
        SongSort.Title -> compareBy(ci) { it.title }
        SongSort.Artist -> compareBy(ci) { it.artist }
        SongSort.Album -> compareBy(ci) { it.album }
        SongSort.Added -> compareBy { it.dateAdded }
        SongSort.Year -> compareBy { it.year }
        SongSort.Duration -> compareBy { it.durationMs }
    }
    val first = if (s.desc) primary.reversed() else primary
    return list.sortedWith(
        first.thenBy(ci) { it.album }.thenBy { it.discNo }.thenBy { it.trackNo }.thenBy(ci) { it.title },
    )
}

@Composable
fun LumenApp(playerState: PlayerState, actions: PlayerActions, themeController: ThemeController) {
    val vm: LibraryViewModel = viewModel()
    val playlistVm: PlaylistViewModel = viewModel()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lumen", Context.MODE_PRIVATE) }

    var granted by remember { mutableStateOf(hasAudioPermission(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(granted) { if (granted) vm.scan() }

    if (!granted) {
        PermissionScreen { launcher.launch(audioPermission()) }
        return
    }

    val tracks by vm.tracks.collectAsState()
    val covers by vm.covers.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val currentId by playerState.currentId.collectAsState()
    val title by playerState.title.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<Detail?>(null) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var immersive by remember { mutableStateOf(false) }
    var sort by remember {
        mutableStateOf(
            SortState(
                SongSort.entries.firstOrNull { it.name == prefs.getString("sort", null) } ?: SongSort.Artist,
                prefs.getBoolean("sortDesc", false),
            ),
        )
    }

    // Dialogs.
    var addTrack by remember { mutableStateOf<Track?>(null) }
    var editing by remember { mutableStateOf<Track?>(null) }
    var renamingArtist by remember { mutableStateOf<String?>(null) }
    var renamingAlbum by remember { mutableStateOf<String?>(null) }
    var coverFor by remember { mutableStateOf<String?>(null) }
    var songArtFor by remember { mutableStateOf<String?>(null) }
    var imageFor by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val album = imageFor
        if (uri != null && album != null) vm.setCoverImage(album, uri)
        imageFor = null
    }

    val menu = SongMenu(
        playNext = { actions.playNext(listOf(it)) },
        addToQueue = { actions.addToQueue(listOf(it)) },
        addToPlaylist = { addTrack = it },
        edit = { editing = it },
        removeFromAlbum = { vm.setAlbum(listOf(it.id), "") },
        goToArtist = {
            detail = Detail.Artist(it.artist.ifBlank { UNKNOWN_ARTIST })
            query = ""
        },
        goToAlbum = {
            if (it.album.isNotBlank()) {
                detail = Detail.Album(it.album)
                query = ""
            }
        },
    )

    BackHandler(immersive) { immersive = false }
    BackHandler(showSettings) { showSettings = false }
    BackHandler(showQueue) { showQueue = false }
    BackHandler(showNowPlaying && !showQueue && !immersive) { showNowPlaying = false }
    BackHandler(!showNowPlaying && query.isNotEmpty()) { query = "" }
    BackHandler(!showNowPlaying && query.isEmpty() && detail != null) { detail = null }

    Box(Modifier.fillMaxSize().background(LumenBg)) {
        Scaffold(
            containerColor = LumenBg,
            bottomBar = {
                Column {
                    if (title != null) {
                        MiniPlayer(playerState, actions, onExpand = { showNowPlaying = true })
                    }
                    BottomTabs(selected = tab) {
                        tab = it
                        detail = null
                        query = ""
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                SearchBar(query, onSettings = { showSettings = true }) { query = it }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val d = detail
                    when {
                        query.isNotBlank() -> {
                            val q = fold(query.trim())
                            val found = remember(tracks, q) { tracks.filter { matchesSearch(it, q) } }
                            SongList(found, currentId, actions, menu)
                        }
                        d is Detail.Artist -> {
                            val list = remember(tracks, d) {
                                tracks.filter { (it.artist.ifBlank { UNKNOWN_ARTIST }) == d.name }
                                    .sortedWith(compareBy({ it.album.lowercase() }, { it.discNo }, { it.trackNo }))
                            }
                            DetailScreen(
                                d.name, "${list.size} songs", null, list, currentId, actions, menu,
                                onRename = { renamingArtist = d.name },
                            ) { detail = null }
                        }
                        d is Detail.Album -> {
                            val list = remember(tracks, d) {
                                tracks.filter { it.album == d.name }.sortedWith(compareBy({ it.discNo }, { it.trackNo }))
                            }
                            val art = remember(list, covers, tracks) { albumArt(list, covers[d.name], tracks) }
                            DetailScreen(
                                d.name, list.firstOrNull()?.artist?.ifBlank { UNKNOWN_ARTIST } ?: "",
                                art, list, currentId, actions, menu,
                                onRename = { renamingAlbum = d.name },
                                onCover = { coverFor = d.name },
                            ) { detail = null }
                        }
                        d is Detail.Playlist -> {
                            val idsFlow = remember(d.id) { playlistVm.trackIds(d.id) }
                            val ids by idsFlow.collectAsState(initial = emptyList<Long>())
                            val list = remember(tracks, ids) {
                                val byId = tracks.associateBy { it.id }
                                ids.mapNotNull { byId[it] }
                            }
                            val playlistMenu = SongMenu(
                                menu.playNext, menu.addToQueue, menu.addToPlaylist, menu.edit,
                                menu.removeFromAlbum, menu.goToArtist, menu.goToAlbum,
                                removeFromPlaylist = { t -> playlistVm.removeFromPlaylist(d.id, t.id) },
                            )
                            DetailScreen(d.name, "${list.size} songs", null, list, currentId, actions, playlistMenu) {
                                detail = null
                            }
                        }
                        tab == 3 -> PlaylistsScreen(playlistVm) { id, name -> detail = Detail.Playlist(id, name) }
                        tab == 4 -> StatsScreen(tracks) { t -> actions.play(listOf(t), 0) }
                        scanning && tracks.isEmpty() -> CenterText("Scanning your music…")
                        tracks.isEmpty() -> EmptyScreen { vm.scan() }
                        tab == 0 -> {
                            val sorted = remember(tracks, sort) { sortTracks(tracks, sort) }
                            Column(Modifier.fillMaxSize()) {
                                SongsHeader(sorted.size, sort) {
                                    sort = it
                                    prefs.edit().putString("sort", it.by.name).putBoolean("sortDesc", it.desc).apply()
                                }
                                SongList(sorted, currentId, actions, menu)
                            }
                        }
                        tab == 1 -> ArtistsScreen(tracks) { detail = Detail.Artist(it) }
                        else -> AlbumsScreen(tracks, covers) { detail = Detail.Album(it.name) }
                    }
                }
            }
        }

        if (showNowPlaying) {
            NowPlaying(
                playerState, actions, prefs,
                onQueue = { showQueue = true },
                onImmersive = { immersive = true },
            ) { showNowPlaying = false }
        }
        if (showQueue) {
            QueueScreen(playerState, actions) { showQueue = false }
        }
        if (immersive) {
            ImmersiveVisualizer(playerState, prefs) { immersive = false }
        }
        if (showSettings) {
            SettingsScreen(themeController, prefs) { showSettings = false }
        }
        addTrack?.let { t ->
            AddToPlaylistDialog(playlistVm, t) { addTrack = null }
        }
        editing?.let { t ->
            val names = remember(tracks) { tracks.map { it.album }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() } }
            EditSongDialog(t, names, onDismiss = { editing = null }) { e ->
                vm.editSong(t, e)
                editing = null
            }
        }
        renamingArtist?.let { name ->
            NameDialog("Rename artist", if (name == UNKNOWN_ARTIST) "" else name, onDismiss = { renamingArtist = null }) { new ->
                vm.renameArtist(if (name == UNKNOWN_ARTIST) "" else name, new)
                detail = Detail.Artist(new)
                renamingArtist = null
            }
        }
        renamingAlbum?.let { name ->
            NameDialog("Rename album", name, onDismiss = { renamingAlbum = null }) { new ->
                vm.renameAlbum(name, new)
                detail = Detail.Album(new)
                renamingAlbum = null
            }
        }
        coverFor?.let { album ->
            CoverDialog(
                album,
                hasOverride = covers.containsKey(album),
                onDismiss = { coverFor = null },
                onCustomImage = {
                    imageFor = album
                    coverFor = null
                    imagePicker.launch("image/*")
                },
                onFromSong = {
                    songArtFor = album
                    coverFor = null
                },
                onReset = {
                    vm.resetCover(album)
                    coverFor = null
                },
            )
        }
        songArtFor?.let { album ->
            SongPickerDialog(tracks, "Use art from a song", onDismiss = { songArtFor = null }) { t ->
                vm.setCoverFromTrack(album, t.id)
                songArtFor = null
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onSettings: () -> Unit, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        placeholder = { Text("Search", color = LumenDim) },
        leadingIcon = {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = LumenDim)
        },
        trailingIcon = {
            IconButton(onClick = onSettings) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings", tint = LumenDim)
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = LumenBg2,
            unfocusedContainerColor = LumenBg2,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = LumenFg,
            unfocusedTextColor = LumenFg,
            cursorColor = LumenAccent,
        ),
    )
}

@Composable
private fun BottomTabs(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = LumenBg2) {
        NavItem(selected == 0, { onSelect(0) }, R.drawable.ic_songs, "Songs")
        NavItem(selected == 1, { onSelect(1) }, R.drawable.ic_artists, "Artists")
        NavItem(selected == 2, { onSelect(2) }, R.drawable.ic_albums, "Albums")
        NavItem(selected == 3, { onSelect(3) }, R.drawable.ic_playlist, "Playlists")
        NavItem(selected == 4, { onSelect(4) }, R.drawable.ic_chart, "Stats")
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    selected: Boolean,
    onClick: () -> Unit,
    iconRes: Int,
    label: String,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(painterResource(iconRes), contentDescription = label) },
        label = { Text(label, fontSize = 11.sp, maxLines = 1) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = LumenOnAccent,
            indicatorColor = LumenAccent,
            selectedTextColor = LumenAccent,
            unselectedIconColor = LumenDim,
            unselectedTextColor = LumenDim,
        ),
    )
}

@Composable
private fun SongsHeader(count: Int, sort: SortState, onSort: (SortState) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count songs", color = LumenDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) {
                Icon(painterResource(R.drawable.ic_sort), contentDescription = null, tint = LumenDim, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("${sort.by.label} ${if (sort.desc) "↓" else "↑"}", color = LumenDim, fontSize = 12.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SongSort.entries.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (s == sort.by) "${s.label}  ${if (sort.desc) "↓" else "↑"}" else s.label,
                                color = if (s == sort.by) LumenAccent else LumenFg,
                            )
                        },
                        // Picking the current sort again flips its direction.
                        onClick = {
                            open = false
                            onSort(if (s == sort.by) sort.copy(desc = !sort.desc) else SortState(s, false))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SongList(tracks: List<Track>, currentId: Long?, actions: PlayerActions, menu: SongMenu) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
            SongRow(track, track.id == currentId, menu) { actions.play(tracks, index) }
        }
    }
}

@Composable
private fun SongRow(track: Track, active: Boolean, menu: SongMenu, onClick: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.artUri, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title.ifBlank { "Unknown title" },
                color = if (active) LumenAccent else LumenFg,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist.ifBlank { UNKNOWN_ARTIST },
                color = LumenDim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(formatDuration(track.durationMs), color = LumenDim, fontSize = 12.sp)
        Box {
            IconButton(onClick = { open = true }) {
                Icon(painterResource(R.drawable.ic_more), contentDescription = "More", tint = LumenDim)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                @Composable
                fun item(label: String, action: (Track) -> Unit) = DropdownMenuItem(
                    text = { Text(label, color = LumenFg) },
                    onClick = {
                        open = false
                        action(track)
                    },
                )
                item("Play next", menu.playNext)
                item("Add to queue", menu.addToQueue)
                item("Add to playlist", menu.addToPlaylist)
                menu.removeFromPlaylist?.let { item("Remove from playlist", it) }
                Divider(color = LumenBg3)
                item("Edit song…", menu.edit)
                if (track.album.isNotBlank()) item("Remove from album", menu.removeFromAlbum)
                Divider(color = LumenBg3)
                item("Go to artist", menu.goToArtist)
                if (track.album.isNotBlank()) item("Go to album", menu.goToAlbum)
            }
        }
    }
}

@Composable
private fun ArtistsScreen(tracks: List<Track>, onOpen: (String) -> Unit) {
    val artists = remember(tracks) {
        tracks.groupBy { it.artist.ifBlank { UNKNOWN_ARTIST } }
            .map { (name, list) -> ArtistItem(name, list.size) }
            .sortedBy { it.name.lowercase() }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(artists, key = { _, a -> a.name }) { _, a ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(a.name) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_artists),
                    contentDescription = null,
                    tint = LumenDim,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, color = LumenFg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${a.count} songs", color = LumenDim, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AlbumsScreen(
    tracks: List<Track>,
    covers: Map<String, com.lumen.android.data.AlbumCoverEntity>,
    onOpen: (AlbumItem) -> Unit,
) {
    val albums = remember(tracks, covers) {
        // Grouped by name, like desktop, so renamed and merged albums come together.
        tracks.filter { it.album.isNotBlank() }.groupBy { it.album }
            .map { (name, list) ->
                AlbumItem(
                    name = name,
                    artist = list.first().artist.ifBlank { UNKNOWN_ARTIST },
                    artUri = albumArt(list, covers[name], tracks),
                    count = list.size,
                )
            }
            .sortedBy { it.name.lowercase() }
    }
    if (albums.isEmpty()) {
        CenterText("No albums yet")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
    ) {
        items(albums, key = { it.name }) { album ->
            Column(
                Modifier.padding(6.dp).clickable { onOpen(album) },
            ) {
                Artwork(album.artUri, Modifier.fillMaxWidth().aspectRatio(1f), cornerDp = 10.dp, decodePx = 320)
                Spacer(Modifier.height(6.dp))
                Text(album.name, color = LumenFg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album.artist, color = LumenDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DetailScreen(
    title: String,
    subtitle: String,
    art: Uri?,
    tracks: List<Track>,
    currentId: Long?,
    actions: PlayerActions,
    menu: SongMenu,
    onRename: (() -> Unit)? = null,
    onCover: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "Back", tint = LumenFg)
            }
            if (onCover != null) {
                Box(Modifier.size(56.dp).clickable(onClick = onCover)) {
                    Artwork(art, Modifier.fillMaxSize(), cornerDp = 8.dp)
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = LumenFg, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, color = LumenDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onCover != null) {
                IconButton(onClick = onCover) {
                    Icon(painterResource(R.drawable.ic_image), contentDescription = "Album cover", tint = LumenDim, modifier = Modifier.size(20.dp))
                }
            }
            if (onRename != null) {
                IconButton(onClick = onRename) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = "Rename", tint = LumenDim, modifier = Modifier.size(20.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (tracks.isNotEmpty()) actions.play(tracks, 0) }) { Text("Play") }
            OutlinedButton(onClick = { actions.addToQueue(tracks) }) { Text("Add to queue", color = LumenFg) }
        }
        Spacer(Modifier.height(4.dp))
        SongList(tracks, currentId, actions, menu)
    }
}

@Composable
private fun MiniPlayer(playerState: PlayerState, actions: PlayerActions, onExpand: () -> Unit) {
    val title by playerState.title.collectAsState()
    val artist by playerState.artist.collectAsState()
    val art by playerState.artUri.collectAsState()
    val isPlaying by playerState.isPlaying.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(LumenBg3)
            .clickable(onClick = onExpand)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art?.let { Uri.parse(it) }, Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title ?: "", color = LumenFg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist ?: "", color = LumenDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = actions.prev) {
            Icon(painterResource(R.drawable.ic_prev), contentDescription = "Previous", tint = LumenFg)
        }
        IconButton(onClick = actions.toggle) {
            Icon(
                painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = "Play/pause",
                tint = LumenAccent,
            )
        }
        IconButton(onClick = actions.next) {
            Icon(painterResource(R.drawable.ic_next), contentDescription = "Next", tint = LumenFg)
        }
    }
}

@Composable
private fun NowPlaying(
    playerState: PlayerState,
    actions: PlayerActions,
    prefs: android.content.SharedPreferences,
    onQueue: () -> Unit,
    onImmersive: () -> Unit,
    onClose: () -> Unit,
) {
    val title by playerState.title.collectAsState()
    val artist by playerState.artist.collectAsState()
    val art by playerState.artUri.collectAsState()
    val isPlaying by playerState.isPlaying.collectAsState()
    val currentId by playerState.currentId.collectAsState()
    val shuffle by playerState.shuffle.collectAsState()
    val repeatMode by playerState.repeatMode.collectAsState()
    val volume by playerState.volume.collectAsState()

    var showViz by remember { mutableStateOf(prefs.getBoolean("showViz", true)) }
    var vizKind by remember { mutableStateOf(VizKind.of(prefs.getString("viz", null))) }
    val fps = remember { prefs.getInt("vizFps", 60) }

    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }

    LaunchedEffect(currentId) {
        while (true) {
            if (!dragging) {
                position = actions.position()
                duration = actions.duration().coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    Surface(color = LumenBg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Close", tint = LumenFg)
                }
                Text("Now playing", color = LumenDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    showViz = !showViz
                    prefs.edit().putBoolean("showViz", showViz).apply()
                }) {
                    Icon(
                        painterResource(if (showViz) R.drawable.ic_art else R.drawable.ic_viz),
                        contentDescription = if (showViz) "Show artwork" else "Show visualizer",
                        tint = LumenDim,
                    )
                }
                IconButton(onClick = onQueue) {
                    Icon(painterResource(R.drawable.ic_queue), contentDescription = "Queue", tint = LumenDim)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (showViz) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
                                .background(LumenBg2).clickable(onClick = onImmersive),
                        ) {
                            Visualizer(vizKind, LocalVizColors.current, fps, Modifier.fillMaxSize().padding(8.dp))
                            Icon(
                                painterResource(R.drawable.ic_fullscreen),
                                contentDescription = "Full screen",
                                tint = LumenDim,
                                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(20.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VizKind.entries.forEach { k ->
                                FilterChip(
                                    selected = k == vizKind,
                                    onClick = {
                                        vizKind = k
                                        prefs.edit().putString("viz", k.id).apply()
                                    },
                                    label = { Text(k.label, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = LumenAccent,
                                        selectedLabelColor = LumenOnAccent,
                                        labelColor = LumenDim,
                                    ),
                                )
                            }
                        }
                    }
                } else {
                    Artwork(art?.let { Uri.parse(it) }, Modifier.fillMaxWidth(0.82f).aspectRatio(1f), cornerDp = 16.dp, decodePx = null)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(title ?: "", color = LumenFg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist ?: "", color = LumenDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

            Spacer(Modifier.height(8.dp))

            val frac = when {
                dragging -> dragFrac
                duration > 0 -> (position.toFloat() / duration).coerceIn(0f, 1f)
                else -> 0f
            }
            Slider(
                value = frac,
                onValueChange = { dragging = true; dragFrac = it },
                onValueChangeFinished = {
                    actions.seek((dragFrac * duration).toLong())
                    dragging = false
                },
                colors = sliderColors(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(if (dragging) (dragFrac * duration).toLong() else position), color = LumenDim, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(duration), color = LumenDim, fontSize = 11.sp)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions.toggleShuffle) {
                    Icon(painterResource(R.drawable.ic_shuffle), contentDescription = "Shuffle", tint = if (shuffle) LumenAccent else LumenDim)
                }
                IconButton(onClick = actions.prev) {
                    Icon(painterResource(R.drawable.ic_prev), contentDescription = "Previous", tint = LumenFg, modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = actions.toggle, modifier = Modifier.size(64.dp)) {
                    Icon(
                        painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = "Play/pause",
                        tint = LumenAccent,
                        modifier = Modifier.size(44.dp),
                    )
                }
                IconButton(onClick = actions.next) {
                    Icon(painterResource(R.drawable.ic_next), contentDescription = "Next", tint = LumenFg, modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = actions.cycleRepeat) {
                    Icon(
                        painterResource(if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                        contentDescription = "Repeat",
                        tint = if (repeatMode == Player.REPEAT_MODE_OFF) LumenDim else LumenAccent,
                    )
                }
            }

            // Volume goes to 150%, like the desktop app; past 100% it's boosted in software.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_volume), contentDescription = "Volume", tint = LumenDim, modifier = Modifier.size(18.dp))
                Slider(
                    value = volume,
                    onValueChange = actions.setVolume,
                    valueRange = 0f..Volume.MAX,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = sliderColors(),
                )
                Text("${(volume * 100).roundToInt()}%", color = LumenDim, fontSize = 11.sp, modifier = Modifier.width(36.dp))
            }
        }
    }
}

/** The visualizer full screen with the system bars hidden. Tap or Back to leave. */
@Composable
private fun ImmersiveVisualizer(
    playerState: PlayerState,
    prefs: android.content.SharedPreferences,
    onExit: () -> Unit,
) {
    val title by playerState.title.collectAsState()
    val artist by playerState.artist.collectAsState()
    val kind = remember { VizKind.of(prefs.getString("viz", null)) }
    val fps = remember { prefs.getInt("vizFps", 60) }
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    Box(Modifier.fillMaxSize().background(LumenBg).clickable(onClick = onExit)) {
        Visualizer(kind, LocalVizColors.current, fps, Modifier.fillMaxSize().padding(16.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            Text(title ?: "", color = LumenFg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist ?: "", color = LumenDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun sliderColors() = SliderDefaults.colors(
    thumbColor = LumenAccent,
    activeTrackColor = LumenAccent,
    inactiveTrackColor = LumenBg3,
)

@Composable
internal fun Artwork(uri: Uri?, modifier: Modifier, cornerDp: Dp = 6.dp, decodePx: Int? = 128) {
    Box(
        modifier.clip(RoundedCornerShape(cornerDp)).background(LumenBg2),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null) {
            val context = LocalContext.current
            val model = remember(uri, decodePx) {
                ImageRequest.Builder(context).data(uri).apply {
                    if (decodePx != null) size(decodePx, decodePx)
                }.build()
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("♪", color = LumenDim, fontSize = 20.sp)
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(LumenBg).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Lumen", color = LumenAccent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "To play your music, Lumen needs permission to read the audio on your phone.",
            color = LumenDim,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text("Grant access") }
    }
}

@Composable
private fun EmptyScreen(onRescan: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No music found", color = LumenFg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text("Nothing turned up in your music folders.", color = LumenDim, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRescan) { Text("Scan again") }
    }
}

@Composable
internal fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = LumenDim, fontSize = 15.sp)
    }
}

private val THEME_CHOICES = listOf(
    "neon" to "Neon",
    "minimal" to "Flashbang",
    "minimal-dark" to "Minimal Dark",
    "retro" to "Retro",
    "glass" to "Glass",
    "blackout" to "Blackout",
    "custom" to "Custom",
)

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, color = LumenDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingsScreen(
    themeController: ThemeController,
    prefs: android.content.SharedPreferences,
    onClose: () -> Unit,
) {
    val selected by themeController.themeId.collectAsState()
    val fromArt by themeController.blackoutFromArt.collectAsState()
    val customTokens by themeController.customTokens.collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var statsEnabled by remember { mutableStateOf(prefs.getBoolean(ListenTracker.KEY_STATS_ENABLED, true)) }
    var confirmClear by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    var fps by remember { mutableStateOf(prefs.getInt("vizFps", 60)) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateResult?>(null) }

    BackHandler(showEditor) { showEditor = false }

    Box(Modifier.fillMaxSize()) {
        Surface(color = LumenBg, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "Back", tint = LumenFg)
                    }
                    Text("Settings", color = LumenFg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(8.dp))
                Text("THEME", color = LumenDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                THEME_CHOICES.chunked(2).forEach { pairRow ->
                    Row(Modifier.fillMaxWidth()) {
                        pairRow.forEach { (id, label) ->
                            val swatchColors = if (id == "custom") colorsFromTokens(customTokens) else previewColors(id)
                            ThemeSwatch(id, label, swatchColors, id == selected, Modifier.weight(1f)) {
                                themeController.setTheme(id)
                            }
                        }
                        if (pairRow.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                if (selected == "blackout") {
                    Spacer(Modifier.height(16.dp))
                    SettingSwitch(
                        "Accent from album art",
                        "Tint the controls with a colour from the cover; off uses a fixed accent",
                        fromArt,
                    ) { themeController.setBlackoutFromArt(it) }
                }

                if (selected == "custom") {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { showEditor = true }) {
                        Text("Edit custom theme")
                    }
                }

                SectionTitle("VISUALIZER")
                Text("Frame rate", color = LumenFg, fontSize = 15.sp)
                Text("Lower is easier on the battery", color = LumenDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60, 30, 15).forEach { f ->
                        FilterChip(
                            selected = fps == f,
                            onClick = {
                                fps = f
                                prefs.edit().putInt("vizFps", f).apply()
                            },
                            label = { Text("$f fps", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LumenAccent,
                                selectedLabelColor = LumenOnAccent,
                                labelColor = LumenDim,
                            ),
                        )
                    }
                }

                SectionTitle("LISTENING STATS")
                SettingSwitch(
                    "Save listening history",
                    "Feeds the Stats tab. Off means nothing gets saved.",
                    statsEnabled,
                ) {
                    statsEnabled = it
                    prefs.edit().putBoolean(ListenTracker.KEY_STATS_ENABLED, it).apply()
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    if (!confirmClear) {
                        confirmClear = true
                    } else {
                        scope.launch { AppDatabase.get(context).statsDao().clear() }
                        confirmClear = false
                        cleared = true
                    }
                }) {
                    Text(
                        when {
                            confirmClear -> "Tap again to clear everything"
                            cleared -> "History cleared"
                            else -> "Clear history"
                        },
                        color = if (confirmClear) Color(0xFFE0607E) else LumenFg,
                    )
                }

                SectionTitle("ABOUT")
                Text("Lumen $version", color = LumenFg, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            update = checkForUpdate(version)
                            checking = false
                        }
                    },
                ) { Text(if (checking) "Checking…" else "Check for updates", color = LumenFg) }
                when (val u = update) {
                    is UpdateResult.Ok -> if (u.upToDate) {
                        Text("You're on the latest version.", color = LumenDim, fontSize = 13.sp)
                    } else {
                        Text("Update available: version ${u.latest}", color = LumenAccent, fontSize = 13.sp)
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url)))
                        }) { Text("Download it from the releases page", color = LumenAccent) }
                    }
                    is UpdateResult.Failed -> Text(u.message, color = LumenDim, fontSize = 13.sp)
                    null -> {}
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        if (showEditor) {
            CustomThemeEditor(themeController) { showEditor = false }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = LumenFg, fontSize = 15.sp)
            Text(hint, color = LumenDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = LumenOnAccent,
                checkedTrackColor = LumenAccent,
                uncheckedThumbColor = LumenDim,
                uncheckedTrackColor = LumenBg3,
            ),
        )
    }
}

@Composable
private fun ThemeSwatch(
    id: String,
    label: String,
    c: LumenColors,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(modifier.padding(6.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.bg)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) LumenAccent else c.border,
                    shape = RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(8.dp, 24.dp).clip(RoundedCornerShape(4.dp)).background(c.accent))
                Box(Modifier.size(8.dp, 16.dp).clip(RoundedCornerShape(4.dp)).background(c.fg))
                Box(Modifier.size(8.dp, 28.dp).clip(RoundedCornerShape(4.dp)).background(c.accent2))
            }
            if (selected) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = "Selected",
                    tint = LumenAccent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = LumenFg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CustomThemeEditor(themeController: ThemeController, onClose: () -> Unit) {
    val tokens by themeController.customTokens.collectAsState()
    var editingKey by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Surface(color = LumenBg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Back", tint = LumenFg)
                }
                Text("Custom theme", color = LumenFg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                Text("START FROM", color = LumenDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    START_FROM.forEach { (id, label) ->
                        OutlinedButton(onClick = { themeController.resetCustomFrom(id) }) {
                            Text(label, fontSize = 12.sp, color = LumenFg)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("COLOURS", color = LumenDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

                EDITABLE_TOKENS.forEach { (key, label) ->
                    val hexStr = tokens[key] ?: ""
                    val swatch = parseHexColor(hexStr) ?: LumenBg3
                    Row(
                        Modifier.fillMaxWidth().clickable { editingKey = key }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(swatch)
                                .border(1.dp, LumenDim, RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, color = LumenFg, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(hexStr.uppercase(), color = LumenDim, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("SHARE", color = LumenDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { clipboard.setText(AnnotatedString(exportTokens(tokens))) }) {
                    Text("Copy theme to clipboard")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it; importError = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Paste a theme") },
                    minLines = 2,
                    maxLines = 4,
                    colors = editorFieldColors(),
                )
                if (importError) {
                    Text("That doesn't look like a Lumen theme.", color = Color(0xFFE0607E), fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val parsed = parseTokens(importText)
                    if (parsed != null) {
                        themeController.replaceCustom(parsed)
                        importText = ""
                        importError = false
                    } else {
                        importError = true
                    }
                }) {
                    Text("Apply pasted theme")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    editingKey?.let { key ->
        ColorPickerDialog(
            initialHex = tokens[key] ?: "#000000",
            onDismiss = { editingKey = null },
        ) { hex -> themeController.setCustomToken(key, hex) }
    }
}

@Composable
private fun ColorPickerDialog(
    initialHex: String,
    onDismiss: () -> Unit,
    onColor: (String) -> Unit,
) {
    val initial = parseHexColor(initialHex) ?: Color.Black
    val startHsv = remember(initialHex) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var h by remember(initialHex) { mutableStateOf(startHsv[0]) }
    var s by remember(initialHex) { mutableStateOf(startHsv[1]) }
    var v by remember(initialHex) { mutableStateOf(startHsv[2]) }
    var hexField by remember(initialHex) { mutableStateOf(colorToHex(initial)) }

    fun pushHsv() {
        val c = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
        hexField = colorToHex(c)
        onColor(hexField)
    }

    val current = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = LumenBg2, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.width(300.dp).padding(20.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(current))
                Spacer(Modifier.height(16.dp))
                Text("Hue", color = LumenDim, fontSize = 12.sp)
                Slider(value = h, onValueChange = { h = it; pushHsv() }, valueRange = 0f..360f, colors = sliderColors())
                Text("Saturation", color = LumenDim, fontSize = 12.sp)
                Slider(value = s, onValueChange = { s = it; pushHsv() }, valueRange = 0f..1f, colors = sliderColors())
                Text("Brightness", color = LumenDim, fontSize = 12.sp)
                Slider(value = v, onValueChange = { v = it; pushHsv() }, valueRange = 0f..1f, colors = sliderColors())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = hexField,
                    onValueChange = { text ->
                        hexField = text
                        val c = parseHexColor(text)
                        if (c != null) {
                            val arr = FloatArray(3)
                            android.graphics.Color.colorToHSV(c.toArgb(), arr)
                            h = arr[0]; s = arr[1]; v = arr[2]
                            onColor(colorToHex(c))
                        }
                    },
                    label = { Text("Hex") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = editorFieldColors(),
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

@Composable
internal fun editorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = LumenFg,
    unfocusedTextColor = LumenFg,
    focusedBorderColor = LumenAccent,
    unfocusedBorderColor = LumenDim,
    cursorColor = LumenAccent,
    focusedLabelColor = LumenDim,
    unfocusedLabelColor = LumenDim,
)

@Composable
private fun PlaylistsScreen(vm: PlaylistViewModel, onOpen: (Long, String) -> Unit) {
    val playlists by vm.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistWithCount?>(null) }
    var deleting by remember { mutableStateOf<PlaylistWithCount?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Playlists", color = LumenFg, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(onClick = { creating = true }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = "New playlist", tint = LumenAccent)
            }
        }
        if (playlists.isEmpty()) {
            CenterText("No playlists yet. Tap + to make one.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(playlists, key = { _, p -> p.id }) { _, p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(p.id, p.name) }
                            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_playlist), contentDescription = null, tint = LumenDim, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = LumenFg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.count} songs", color = LumenDim, fontSize = 12.sp)
                        }
                        IconButton(onClick = { renaming = p }) {
                            Icon(painterResource(R.drawable.ic_edit), contentDescription = "Rename", tint = LumenDim, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { deleting = p }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete", tint = LumenDim, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog("New playlist", "", onDismiss = { creating = false }) { name ->
            vm.create(name)
            creating = false
        }
    }
    renaming?.let { r ->
        NameDialog("Rename playlist", r.name, onDismiss = { renaming = null }) { name ->
            vm.rename(r.id, name)
            renaming = null
        }
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = {
                TextButton(onClick = { vm.delete(d.id); deleting = null }) {
                    Text("Delete", color = Color(0xFFE0607E))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel", color = LumenDim) }
            },
            title = { Text("Delete playlist", color = LumenFg) },
            text = { Text("Delete \"${d.name}\"? The songs stay in your library.", color = LumenDim) },
            containerColor = LumenBg2,
        )
    }
}

@Composable
private fun AddToPlaylistDialog(vm: PlaylistViewModel, track: Track, onDone: () -> Unit) {
    val playlists by vm.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDone) {
        Surface(color = LumenBg2, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.width(300.dp).padding(vertical = 16.dp)) {
                Text(
                    "Add to playlist",
                    color = LumenFg,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(8.dp))
                if (playlists.isEmpty()) {
                    Text(
                        "No playlists yet",
                        color = LumenDim,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                } else {
                    playlists.forEach { pl ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { vm.addToPlaylist(pl.id, track.id); onDone() }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.ic_playlist), contentDescription = null, tint = LumenDim, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(pl.name, color = LumenFg, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${pl.count}", color = LumenDim, fontSize = 12.sp)
                        }
                    }
                }
                Divider(color = LumenBg3)
                Row(
                    Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = LumenAccent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("New playlist", color = LumenAccent, fontSize = 14.sp)
                }
            }
        }
    }

    if (creating) {
        NameDialog("New playlist", "", onDismiss = { creating = false }) { name ->
            vm.createAndAdd(name, track.id)
            creating = false
            onDone()
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = LumenBg2, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.width(300.dp).padding(20.dp)) {
                Text(title, color = LumenFg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = editorFieldColors(),
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel", color = LumenDim) }
                    Button(onClick = { if (text.isNotBlank()) onSubmit(text.trim()) }) { Text("Save") }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
