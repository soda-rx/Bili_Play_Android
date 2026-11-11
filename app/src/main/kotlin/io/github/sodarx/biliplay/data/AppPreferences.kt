package io.github.sodarx.biliplay.data

import android.content.Context

data class AppearanceSettings(
    val themeKey: String = "blue",
    val systemColor: Boolean = false,
    val appearance: String = "system",
    val recordHistory: Boolean = true,
    val recordSearch: Boolean = true,
    val clickPolicy: String = "append",
    val smartSongTitles: Boolean = true,
)

class AppPreferences(context: Context, profileId: String = "guest") {
    private val preferences =
        context.getSharedPreferences(
            "profile_${validProfile(profileId)}.settings",
            Context.MODE_PRIVATE,
        )

    fun read(): AppearanceSettings {
        val themes =
            setOf(
                "blue",
                "indigo",
                "cyan",
                "teal",
                "green",
                "olive",
                "gold",
                "orange",
                "coral",
                "rose",
                "purple",
                "graphite",
            )
        return AppearanceSettings(
            preferences.getString("theme_key", "blue")?.takeIf { it in themes } ?: "blue",
            preferences.getBoolean("system_color", false),
            preferences.getString("appearance", "system")?.takeIf {
                it in listOf("system", "light", "dark")
            } ?: "system",
            preferences.getBoolean("record_history", true),
            preferences.getBoolean("record_search", true),
            preferences.getString("click_policy", "append")?.takeIf {
                it in listOf("append", "replace", "ask")
            } ?: "append",
            preferences.getBoolean("smart_song_titles", true),
        )
    }

    fun write(v: AppearanceSettings) {
        preferences
            .edit()
            .putString("theme_key", v.themeKey)
            .putBoolean("system_color", v.systemColor)
            .putString("appearance", v.appearance)
            .putBoolean("record_history", v.recordHistory)
            .putBoolean("record_search", v.recordSearch)
            .putString("click_policy", v.clickPolicy)
            .putBoolean("smart_song_titles", v.smartSongTitles)
            .apply()
    }
}
