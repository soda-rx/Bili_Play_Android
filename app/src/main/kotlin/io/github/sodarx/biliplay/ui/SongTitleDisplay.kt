package io.github.sodarx.biliplay.ui

import androidx.compose.runtime.*
import io.github.sodarx.biliplay.core.SongTitles

internal val LocalSmartTitles = staticCompositionLocalOf { true }

@Composable
internal fun songTitle(raw: String): String {
    val enabled = LocalSmartTitles.current
    return remember(raw, enabled) { SongTitles.display(raw, enabled) }
}
