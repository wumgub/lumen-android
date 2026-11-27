package com.lumen.android.theme

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Holds the chosen theme, the Blackout accent-source setting, and the custom
 * theme's token map — all persisted across launches. `dynamicAccent` (ARGB int)
 * is the colour sampled from the current album art for Blackout's "accent from
 * album art" mode; null means fall back to the fixed accent.
 */
class ThemeController(private val prefs: SharedPreferences) {

    val themeId = MutableStateFlow(prefs.getString(KEY_THEME, "neon") ?: "neon")
    val blackoutFromArt = MutableStateFlow(prefs.getBoolean(KEY_BLACKOUT_ART, true))
    val dynamicAccent = MutableStateFlow<Int?>(null)
    val customTokens = MutableStateFlow(loadCustom())

    /** App icon: follow the theme, or [iconChoice]. Custom has its own pick. */
    val iconFollowsTheme = MutableStateFlow(prefs.getBoolean(KEY_ICON_FOLLOWS, true))
    val iconChoice = MutableStateFlow(prefs.getString(KEY_ICON, null) ?: IconSwitcher.DEFAULT_ICON)
    val customIcon = MutableStateFlow(prefs.getString(KEY_CUSTOM_ICON, null))

    fun setTheme(id: String) {
        themeId.value = id
        prefs.edit().putString(KEY_THEME, id).apply()
    }

    fun setBlackoutFromArt(enabled: Boolean) {
        blackoutFromArt.value = enabled
        prefs.edit().putBoolean(KEY_BLACKOUT_ART, enabled).apply()
    }

    fun setDynamicAccent(argb: Int?) {
        dynamicAccent.value = argb
    }

    fun setCustomToken(key: String, hex: String) {
        val updated = customTokens.value.toMutableMap().apply { this[key] = hex }
        customTokens.value = updated
        persistCustom(updated)
    }

    fun resetCustomFrom(baseId: String) {
        val updated = (PRESET_TOKENS[baseId] ?: defaultCustom()).toMap()
        customTokens.value = updated
        persistCustom(updated)
    }

    /** Merge imported tokens over the current map so unspecified keys are kept. */
    fun replaceCustom(tokens: Map<String, String>) {
        val updated = customTokens.value.toMutableMap().apply { putAll(tokens) }
        customTokens.value = updated
        persistCustom(updated)
    }

    fun setIconFollowsTheme(follows: Boolean) {
        iconFollowsTheme.value = follows
        prefs.edit().putBoolean(KEY_ICON_FOLLOWS, follows).apply()
    }

    fun setIconChoice(id: String) {
        iconChoice.value = id
        prefs.edit().putString(KEY_ICON, id).apply()
    }

    fun setCustomIcon(id: String) {
        customIcon.value = id
        prefs.edit().putString(KEY_CUSTOM_ICON, id).apply()
    }

    /** Custom's icon: the one picked in its editor, else the closest to its accent. */
    fun customIconId(tokens: Map<String, String> = customTokens.value): String =
        customIcon.value ?: tokens["accent"]?.let { hex ->
            hex.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.let(IconSwitcher::nearest)
        } ?: IconSwitcher.DEFAULT_ICON

    /** The icon the launcher should show right now. */
    fun iconId(): String = when {
        !iconFollowsTheme.value -> iconChoice.value
        themeId.value == "custom" -> customIconId()
        else -> APP_ICONS.firstOrNull { it.id == themeId.value }?.id ?: IconSwitcher.DEFAULT_ICON
    }

    private fun loadCustom(): Map<String, String> {
        val stored = prefs.getString(KEY_CUSTOM, null) ?: return defaultCustom()
        return parseTokens(stored) ?: defaultCustom()
    }

    private fun persistCustom(tokens: Map<String, String>) {
        prefs.edit().putString(KEY_CUSTOM, exportTokens(tokens)).apply()
    }

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_BLACKOUT_ART = "blackoutFromArt"
        private const val KEY_CUSTOM = "customTokens"
        private const val KEY_ICON_FOLLOWS = "iconFollowsTheme"
        private const val KEY_ICON = "appIcon"
        private const val KEY_CUSTOM_ICON = "customIcon"
    }
}
