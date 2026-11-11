package io.github.sodarx.biliplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.sodarx.biliplay.core.Track

internal val SongArtworkShape = RoundedCornerShape(percent = 20)
internal val LocalDiscRotation = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }
internal val LocalPlaybackActive = staticCompositionLocalOf { false }

@Composable
internal fun SongArtwork(track: Track, modifier: Modifier, compact: Boolean = false) {
    val current = LocalNowPlaying.current == track.key
    Box(
        modifier
            .aspectRatio(1f)
            .clip(SongArtworkShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        AsyncImage(
            artworkRequest(track.cover),
            null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (current)
            Surface(
                Modifier.align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(if (compact) 18.dp else 26.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (LocalPlaybackActive.current) Icons.Default.GraphicEq
                        else Icons.Default.Pause,
                        if (LocalPlaybackActive.current) "正在播放" else "当前歌曲已暂停",
                        Modifier.padding(3.dp).fillMaxSize(),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
    }
}
