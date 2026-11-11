@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.sodarx.biliplay.core.*
import io.github.sodarx.biliplay.core.Track

@Composable
internal fun FavoritesScreen(s: AppState, m: BiliPlayViewModel) {
    if (s.account == null) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("尚未登录")
            TextButton(onClick = m::startLogin) { Text("登录") }
        }
        return
    }
    val folders = m.sortedFolders()
    if (folders.isEmpty() && !s.loading) {
        EmptyContent(s.pageError ?: "暂无收藏夹", m::refreshFolders)
        return
    }
    ReorderableFavorites(s, m, folders)
}

@Composable
internal fun LargeFolderPreview(covers: List<String>, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (covers.isEmpty())
            Box(
                Modifier.fillMaxSize()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.LibraryMusic, "暂无封面", Modifier.size(40.dp))
            }
        else
            covers.take(3).reversed().forEachIndexed { index, url ->
                val depth = minOf(covers.size, 3) - 1 - index
                AsyncImage(
                    artworkRequest(url),
                    null,
                    Modifier.fillMaxSize()
                        .padding(end = 6.dp, bottom = 6.dp)
                        .graphicsLayer {
                            rotationZ = -depth * 3f
                            scaleX = 1f - depth * .035f
                            scaleY = scaleX
                            translationX = depth * 3.dp.toPx()
                            translationY = -depth * 2.dp.toPx()
                        }
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentScale = ContentScale.Crop,
                )
            }
    }
}

@Composable
internal fun FolderScreen(s: AppState, m: BiliPlayViewModel) {
    LazyColumn {
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${s.selectedFolder?.count ?: 0} 首",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = { m.playAll(s.folderTracks) },
                    enabled = s.folderTracks.isNotEmpty(),
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text("播放全部")
                }
            }
        }
        items(s.folderTracks, key = { it.bvid + ":" + it.page }) { track ->
            FeatureTrackRow(track, m, onPlay = { m.play(track, s.folderTracks) })
        }
        item { LoadMore(s.folderHasMore, s, m) }
        if (s.folderTracks.isEmpty() && !s.loading)
            item { EmptyContent(s.pageError ?: "暂无歌曲", { m.loadFolder() }) }
    }
}

@Composable
internal fun SearchResults(s: AppState, m: BiliPlayViewModel) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = s.searchKind == "songs",
                onClick = { m.search("songs") },
                label = { Text("歌曲") },
            )
            FilterChip(
                selected = s.searchKind == "uploaders",
                onClick = { m.search("uploaders") },
                label = { Text("UP 主") },
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = LocalPlayerContentBottom.current)) {
            if (s.searchKind == "songs")
                items(s.results, key = { it.bvid + ":" + it.page }) { FeatureTrackRow(it, m) }
            else items(s.uploaders, key = { it.id }) { UserRow(it, m) }
            item { LoadMore(s.searchHasMore, s, m) }
            if (
                (if (s.searchKind == "songs") s.results.isEmpty() else s.uploaders.isEmpty()) &&
                    !s.loading
            )
                item { EmptyContent(s.pageError ?: "暂无结果") }
        }
    }
}

@Composable
internal fun FeatureHistory(s: AppState, m: BiliPlayViewModel) {
    if (s.history.isEmpty()) EmptyContent("暂无播放记录")
    else
        LazyColumn {
            items(s.history, key = { it.key }) { FeatureTrackRow(it, m) }
        }
}

@Composable
internal fun SearchSuggestions(
    state: AppState,
    model: BiliPlayViewModel,
    onQuery: (String) -> Unit,
    onPlay: (Track) -> Unit,
) {

    LazyColumn {
        if (state.query.isBlank())
            items(state.searchHistory) { query ->
                ListItem(
                    onClick = {
                        onQuery(query)
                    },
                    content = { Text(query) },
                    leadingContent = { Icon(Icons.Default.History, null) },
                    trailingContent = {
                        IconButton(onClick = { model.deleteSearch(query) }) {
                            Icon(Icons.Default.Close, "删除此搜索记录")
                        }
                    },
                )
            }
        else {
            if (state.suggestions.isNotEmpty())
                item {
                    Text(
                        "搜索联想",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            items(state.suggestions) { query ->
                ListItem(
                    onClick = {
                        onQuery(query)
                    },
                    content = { Text(query) },
                    leadingContent = { Icon(Icons.Default.Search, null) },
                )
            }
            items(state.suggestionTracks, key = { it.bvid }) { track ->
                FeatureTrackRow(track, model) {
                    onPlay(track)
                }
            }
        }
    }
}
