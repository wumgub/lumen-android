package com.lumen.android.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumen.android.R
import com.lumen.android.playback.PlayerActions
import com.lumen.android.playback.PlayerState

/** The play queue: tap a song to jump to it, ✕ to drop it. */
@Composable
fun QueueScreen(playerState: PlayerState, actions: PlayerActions, onClose: () -> Unit) {
    val queue by playerState.queue.collectAsState()
    val index by playerState.index.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { if (index > 0) listState.scrollToItem(index) }

    Surface(color = LumenBg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Back", tint = LumenFg)
                }
                Column(Modifier.weight(1f)) {
                    Text("Queue", color = LumenFg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    val upcoming = (queue.size - index - 1).coerceAtLeast(0)
                    Text("$upcoming up next", color = LumenDim, fontSize = 12.sp)
                }
                if (index + 1 < queue.size) {
                    TextButton(onClick = actions.clearUpcoming) { Text("Clear upcoming", color = LumenAccent) }
                }
            }
            if (queue.isEmpty()) {
                CenterText("Nothing queued")
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(queue) { i, entry ->
                        val current = i == index
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (current) LumenBg2 else LumenBg)
                                .clickable { actions.jumpTo(i) }
                                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(entry.artUri?.let { Uri.parse(it) }, Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.title,
                                    color = if (current) LumenAccent else if (i < index) LumenDim else LumenFg,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(entry.artist.ifBlank { UNKNOWN_ARTIST }, color = LumenDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { actions.removeAt(i) }) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove", tint = LumenDim, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
