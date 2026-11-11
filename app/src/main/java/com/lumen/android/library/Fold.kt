package com.lumen.android.library

import java.text.Normalizer

/**
 * Case- and accent-insensitive form of a string for search, the same as the
 * desktop app's: decompose, drop accents from Latin, Greek and Cyrillic
 * letters, lower-case. So `кино` finds `Кино`, `ё` matches `е` and `é`
 * matches `e`. Marks in other scripts are part of the letter (Japanese
 * dakuten, Hindi and Thai vowel signs), so they stay, and katakana folds to
 * hiragana so `カル` finds `かる`.
 */
fun fold(s: String): String {
    val out = StringBuilder(s.length)
    var accented = false // the last letter takes droppable accents
    val decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD)
    var i = 0
    while (i < decomposed.length) {
        val c = decomposed.codePointAt(i)
        i += Character.charCount(c)
        when (Character.getType(c)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> if (!accented) out.appendCodePoint(c)
            else -> {
                accented = c <= 0x052F || c in 0x1E00..0x1FFF
                out.append(String(Character.toChars(c)).lowercase())
            }
        }
    }
    val composed = Normalizer.normalize(out, Normalizer.Form.NFC)
    return buildString(composed.length) {
        for (ch in composed) append(if (ch in 'ァ'..'ヶ') ch - 0x60 else ch)
    }
}
