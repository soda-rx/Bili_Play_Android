@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** Inline input panel: no dialog/window, no message data written to disk. */
@Composable
internal fun ComposerEmotes(s: AppState, m: BiliPlayViewModel, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        if (s.emotes.isEmpty()) {
            if (s.loading)
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    CircularWavyProgressIndicator(Modifier.size(28.dp))
                }
            else EmptyContent(s.pageError ?: "暂无表情", m::emotes)
        } else
            LazyVerticalGrid(
                columns = GridCells.Adaptive(56.dp),
                contentPadding = PaddingValues(12.dp),
            ) {
                s.emotes.forEach { pack ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            pack.name,
                            Modifier.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    items(pack.emotes) { emote ->
                        TextButton(
                            onClick = { m.insertEmote(emote.text) },
                            contentPadding = PaddingValues(8.dp),
                        ) {
                            if (emote.url.isNotBlank())
                                AsyncImage(emote.url, emote.text, Modifier.size(36.dp))
                            else Text(emote.text)
                        }
                    }
                }
            }
    }
}
