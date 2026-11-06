package com.lumen.android.viz

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lumen.android.playback.AudioTap

private class Clock {
    val startNs = System.nanoTime()
    var lastNs = 0L
    var dt = 1 / 60f
}

/**
 * A live visualizer. It pulls audio from [AudioTap] each frame (capped at
 * [fps]), runs the analyser and redraws. On Android 12 and newer a blurred copy
 * underneath gives the neon glow the desktop gets from canvas shadows.
 */
@Composable
fun Visualizer(kind: VizKind, colors: VizColors, fps: Int, modifier: Modifier = Modifier) {
    val spectrum = remember { Spectrum() }
    val state = remember { VizState() }
    val clock = remember { Clock() }
    var frameNs by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        AudioTap.enabled = true
        onDispose { AudioTap.enabled = false }
    }
    LaunchedEffect(kind) { state.reset() }
    LaunchedEffect(fps) {
        val minGap = 1_000_000_000L / fps.coerceIn(10, 120)
        while (true) {
            withFrameNanos { now ->
                // A little slack so a 60 fps cap on a 60 Hz screen doesn't skip frames.
                if (now - clock.lastNs >= minGap - 2_000_000L) {
                    clock.dt = if (clock.lastNs == 0L) 1 / 60f else ((now - clock.lastNs) / 1e9f).coerceAtMost(0.25f)
                    clock.lastNs = now
                    if (!AudioTap.read(spectrum.samples)) spectrum.samples.fill(0f)
                    spectrum.analyse(clock.dt)
                    frameNs = now
                }
            }
        }
    }

    val blur = with(LocalDensity.current) { 10.dp.toPx() }
    Box(modifier) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    renderEffect = BlurEffect(blur, blur, TileMode.Decal)
                    alpha = 0.9f
                },
            ) {
                val t = (frameNs - clock.startNs) / 1e9f
                drawVisualizer(kind, spectrum.frame, colors, state, t, clock.dt, glowPass = true)
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val t = (frameNs - clock.startNs) / 1e9f
            drawVisualizer(kind, spectrum.frame, colors, state, t, clock.dt, glowPass = false)
        }
    }
}
