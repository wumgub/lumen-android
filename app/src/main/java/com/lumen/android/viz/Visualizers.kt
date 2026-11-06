package com.lumen.android.viz

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** The three visualizers, ported from the desktop's visualizers/*.ts with the same maths. */
enum class VizKind(val id: String, val label: String) {
    Bars("bars", "Spectrum Bars"),
    Waveform("waveform", "Waveform"),
    Radial("radial", "Radial");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Bars
    }
}

data class VizColors(val accent: Color, val accent2: Color, val fg: Color)

class VizState {
    val peaks = FloatArray(64)
    fun reset() = peaks.fill(0f)
}

/**
 * Draw one frame. [glowPass] draws only the soft colour that goes into the
 * blurred layer underneath (on Android 12+); the sharp pass draws everything.
 */
fun DrawScope.drawVisualizer(
    kind: VizKind,
    frame: AudioFrame,
    colors: VizColors,
    state: VizState,
    t: Float,
    dt: Float,
    glowPass: Boolean,
) {
    when (kind) {
        VizKind.Bars -> bars(frame, colors, state, dt, glowPass)
        VizKind.Waveform -> waveform(frame, colors, glowPass)
        VizKind.Radial -> radial(frame, colors, t, glowPass)
    }
}

private fun avg(freq: IntArray, from: Int, to: Int): Float = Spectrum.band(freq, from, to)

/** Classic spectrum-analyser bars rising from the baseline. */
private fun DrawScope.bars(frame: AudioFrame, c: VizColors, s: VizState, dt: Float, glowPass: Boolean) {
    val bars = 64
    val gap = 2f * density
    val usable = floor(frame.freq.size * 0.7f)
    val step = usable / bars
    val barW = (size.width - gap * (bars - 1)) / bars
    val baseline = size.height * 0.92f
    // The browser dropped peak caps 0.012 per frame at 60 fps.
    val fall = 0.012f * dt * 60f
    for (i in 0 until bars) {
        val from = floor(i * step).toInt()
        val to = floor((i + 1) * step).toInt()
        val v = avg(frame.freq, from, to).pow(0.8f) * (1 + i.toFloat() / bars)
        val h = min(1f, v) * baseline * 0.95f
        val x = i * (barW + gap)
        val color = if (i < bars / 2) c.accent else c.accent2
        if (h > 0.5f) {
            val brush = Brush.verticalGradient(
                0f to color,
                1f to color.copy(alpha = 0.25f),
                startY = baseline - h,
                endY = baseline,
            )
            val r = min(min(barW / 2, 4f * density), h / 2)
            drawRoundRect(brush, Offset(x, baseline - h), Size(barW, h), CornerRadius(r, r))
        }
        if (!glowPass) {
            s.peaks[i] = maxOf(s.peaks[i] - fall, h / baseline)
            val py = baseline - s.peaks[i] * baseline
            drawRect(c.fg, Offset(x, py - 2 * density), Size(barW, 2 * density))
        }
    }
}

/** Centred oscilloscope with a mirrored echo. */
private fun DrawScope.waveform(frame: AudioFrame, c: VizColors, glowPass: Boolean) {
    val wave = frame.wave
    val mid = size.height / 2
    val amp = size.height * 0.4f * (0.4f + frame.level * 2)
    val slice = size.width / wave.size
    fun path(mirror: Boolean) = Path().apply {
        for (i in wave.indices) {
            val v = (wave[i] - 128) / 128f
            val y = mid + (if (mirror) -v else v) * amp
            if (i == 0) moveTo(0f, y) else lineTo(i * slice, y)
        }
    }
    if (!glowPass) drawLine(c.fg.copy(alpha = 0.08f), Offset(0f, mid), Offset(size.width, mid), 1f * density)
    val widen = if (glowPass) 3f else 1f
    drawPath(path(true), c.accent2.copy(alpha = 0.4f), style = Stroke(2f * density * widen, join = StrokeJoin.Round))
    drawPath(path(false), c.accent, style = Stroke(2.5f * density * widen, join = StrokeJoin.Round))
}

/** Frequency bars wrapped into a ring, with a pulsing core. */
private fun DrawScope.radial(frame: AudioFrame, c: VizColors, t: Float, glowPass: Boolean) {
    val bars = 96
    val cx = size.width / 2
    val cy = size.height / 2
    val usable = floor(frame.freq.size * 0.6f)
    val step = usable / bars
    val short = min(size.width, size.height)
    val baseR = short * 0.16f * (1 + frame.bass * 0.35f)
    val maxLen = short * 0.3f
    val rot = t * 0.15f
    val center = Offset(cx, cy)
    if (!glowPass) {
        drawCircle(
            Brush.radialGradient(
                listOf(c.accent.copy(alpha = (0.5f + frame.bass * 0.4f).coerceAtMost(1f)), c.accent.copy(alpha = 0f)),
                center = center,
                radius = baseR * 1.4f,
            ),
            radius = baseR * 1.4f,
            center = center,
        )
    }
    for (i in 0 until bars) {
        val from = floor(i * step).toInt()
        val to = floor((i + 1) * step).toInt()
        val len = avg(frame.freq, from, to).pow(0.85f) * maxLen
        val angle = (i.toFloat() / bars) * 2 * PI.toFloat() + rot
        val dx = cos(angle)
        val dy = sin(angle)
        val color = if (i % 2 == 0) c.accent else c.accent2
        drawLine(
            color,
            Offset(cx + dx * baseR, cy + dy * baseR),
            Offset(cx + dx * (baseR + len), cy + dy * (baseR + len)),
            strokeWidth = 3f * density * (if (glowPass) 2f else 1f),
            cap = StrokeCap.Round,
        )
    }
    if (!glowPass) drawCircle(c.fg.copy(alpha = 0.15f), baseR, center, style = Stroke(1f * density))
}
