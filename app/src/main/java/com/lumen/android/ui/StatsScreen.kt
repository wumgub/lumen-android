package com.lumen.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumen.android.data.AppDatabase
import com.lumen.android.data.PlayEntity
import com.lumen.android.model.Track
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private const val WEEKS = 12

data class TopRow(val track: Track, val plays: Int, val seconds: Long)
data class WeekBucket(val start: LocalDate, val seconds: Long, val plays: Int)
data class StatsSummary(
    val top: List<TopRow>,
    val weeks: List<WeekBucket>,
    val totalSeconds: Long,
    val totalPlays: Int,
)

private fun mondayOf(ms: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/**
 * The same numbers as the desktop Stats view: a most-played leaderboard (top
 * 30, plays then time) and the last 12 calendar weeks, bucketed by local
 * Monday so daylight saving can't shift a week.
 */
fun summarise(plays: List<PlayEntity>, tracks: List<Track>, nowMs: Long = System.currentTimeMillis()): StatsSummary {
    val zone = ZoneId.systemDefault()
    val byId = tracks.associateBy { it.id }
    val perTrack = HashMap<Long, Pair<Int, Long>>()
    var totalSeconds = 0L
    var totalPlays = 0
    for (p in plays) {
        val c = if (p.counted) 1 else 0
        totalSeconds += p.seconds
        totalPlays += c
        val (n, s) = perTrack[p.trackId] ?: (0 to 0L)
        perTrack[p.trackId] = (n + c) to (s + p.seconds)
    }
    val top = perTrack.mapNotNull { (id, a) ->
        val t = byId[id] ?: return@mapNotNull null
        if (a.first > 0) TopRow(t, a.first, a.second) else null
    }.sortedWith(compareByDescending<TopRow> { it.plays }.thenByDescending { it.seconds }).take(30)

    val thisMonday = mondayOf(nowMs, zone)
    val starts = (WEEKS - 1 downTo 0).map { thisMonday.minusWeeks(it.toLong()) }
    val index = starts.withIndex().associate { (i, d) -> d to i }
    val secs = LongArray(WEEKS)
    val counts = IntArray(WEEKS)
    for (p in plays) {
        val i = index[mondayOf(p.playedAt, zone)] ?: continue
        secs[i] += p.seconds
        if (p.counted) counts[i]++
    }
    return StatsSummary(top, starts.mapIndexed { i, d -> WeekBucket(d, secs[i], counts[i]) }, totalSeconds, totalPlays)
}

/** "5h 27m", like the desktop's formatDuration. */
fun formatTotal(seconds: Long): String {
    if (seconds <= 0) return "0m"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

@Composable
fun StatsScreen(tracks: List<Track>, onPlay: (Track) -> Unit) {
    val context = LocalContext.current
    val playsFlow = remember { AppDatabase.get(context).statsDao().plays() }
    val plays by playsFlow.collectAsState(initial = null)
    val list = plays
    if (list == null) {
        CenterText("Loading…")
        return
    }
    if (list.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No listening yet", color = LumenFg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Play some music. Your most-played songs and weekly totals will show up here.",
                color = LumenDim,
                fontSize = 14.sp,
            )
        }
        return
    }
    val s = remember(list, tracks) { summarise(list, tracks) }
    val thisWeek = s.weeks.last()
    val maxPlays = (s.top.maxOfOrNull { it.plays } ?: 1).coerceAtLeast(1)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Text("Listening", color = LumenFg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Total", formatTotal(s.totalSeconds), "${s.totalPlays} plays", Modifier.weight(1f))
                StatTile("This week", formatTotal(thisWeek.seconds), "${thisWeek.plays} plays", Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            Text("LAST 12 WEEKS", color = LumenDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            WeekChart(s.weeks)
            Spacer(Modifier.height(20.dp))
            Text("MOST PLAYED", color = LumenDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
        }
        itemsIndexed(s.top, key = { _, r -> r.track.id }) { i, r ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable { onPlay(r.track) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = LumenDim, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                Artwork(r.track.artUri, Modifier.size(40.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.track.title, color = LumenFg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.track.artist.ifBlank { UNKNOWN_ARTIST }, color = LumenDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(LumenBg3)) {
                        Box(
                            Modifier.fillMaxWidth(r.plays.toFloat() / maxPlays).height(3.dp)
                                .clip(RoundedCornerShape(2.dp)).background(LumenAccent),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${r.plays}", color = LumenFg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(formatTotal(r.seconds), color = LumenDim, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(LumenBg2).padding(14.dp)) {
        Text(label, color = LumenDim, fontSize = 12.sp)
        Text(value, color = LumenFg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, color = LumenDim, fontSize = 12.sp)
    }
}

@Composable
private fun WeekChart(weeks: List<WeekBucket>) {
    val accent = LumenAccent
    val track = LumenBg3
    val maxSeconds = (weeks.maxOfOrNull { it.seconds } ?: 1L).coerceAtLeast(1L)
    val tick = DateTimeFormatter.ofPattern("d/M")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LumenBg2).padding(12.dp)) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val gap = 6.dp.toPx()
            val w = (size.width - gap * (weeks.size - 1)) / weeks.size
            weeks.forEachIndexed { i, wk ->
                val x = i * (w + gap)
                drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(4.dp.toPx()))
                val h = size.height * wk.seconds / maxSeconds
                if (h > 0f) {
                    drawRoundRect(accent, Offset(x, size.height - h), Size(w, h), CornerRadius(4.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(weeks.first().start.format(tick), color = LumenDim, fontSize = 11.sp)
            Text("this week", color = LumenDim, fontSize = 11.sp)
        }
    }
}
