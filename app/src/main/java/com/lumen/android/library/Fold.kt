package com.lumen.android.library

import java.text.Normalizer

private val MARKS = Regex("\\p{M}+")

/**
 * Case- and accent-insensitive form of a string for search, the same as the
 * desktop app's: decompose, drop combining marks, lower-case. So `кино` finds
 * `Кино`, `ё` matches `е` and `é` matches `e`.
 */
fun fold(s: String): String =
    MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase()
