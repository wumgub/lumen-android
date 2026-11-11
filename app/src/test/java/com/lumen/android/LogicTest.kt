package com.lumen.android

import com.lumen.android.library.fold
import com.lumen.android.theme.exportTokens
import com.lumen.android.theme.parseTokens
import com.lumen.android.update.compareVersions
import com.lumen.android.viz.BINS
import com.lumen.android.viz.FFT_SIZE
import com.lumen.android.viz.Spectrum
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test
    fun foldMatchesAcrossCaseAndAccents() {
        assertEquals("кино", fold("Кино"))
        assertEquals("елка", fold("Ёлка"))
        assertEquals("beyonce", fold("Beyoncé"))
        assertEquals("rob co chcesz", fold("Rób Co Chcesz"))
    }

    @Test
    fun foldKeepsLettersWholeOutsideLatin() {
        assertEquals(fold("が"), fold("ガ"))
        assertNotEquals(fold("か"), fold("が"))
        assertEquals(fold("ガル"), fold("ｶﾞﾙ"))
        assertEquals("ひかる", fold("ヒカル"))
        assertEquals("米津玄師", fold("米津玄師"))
        assertEquals("아이유", fold("아이유"))
        assertNotEquals(fold("शकर"), fold("शंकर"))
    }

    @Test
    fun versionsCompareNumerically() {
        assertTrue(compareVersions("0.10.0", "0.9.2") > 0)
        assertEquals(0, compareVersions("v0.4.0", "0.4"))
        assertTrue(compareVersions("0.3.2", "0.4.0") < 0)
    }

    @Test
    fun themesRoundTrip() {
        val tokens = mapOf("bg" to "#000000", "accent" to "#22d3ee")
        assertEquals(tokens, parseTokens(exportTokens(tokens)))
        assertEquals("#000000", parseTokens("```json\n{\"tokens\":{\"bg\":\"#000000\"}}\n```")?.get("bg"))
    }

    @Test
    fun aSineLandsInTheRightBin() {
        val s = Spectrum()
        val rate = 48_000f
        val hz = 1_000f
        for (i in 0 until FFT_SIZE) s.samples[i] = (sin(2 * PI * hz * i / rate) * 0.5).toFloat()
        repeat(30) { s.analyse(1 / 60f) }
        val peak = (0 until BINS).maxByOrNull { s.frame.freq[it] }!!
        val expected = (hz / (rate / FFT_SIZE)).roundToInt()
        assertTrue("peak $peak, expected ~$expected", abs(peak - expected) <= 1)
        assertTrue(s.frame.freq[peak] > 200)
        assertTrue(s.frame.level > 0.3f)
    }

    @Test
    fun silenceIsZero() {
        val s = Spectrum()
        s.analyse(1 / 60f)
        assertTrue(s.frame.freq.all { it == 0 })
        assertTrue(s.frame.wave.all { it == 128 })
    }
}
