@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.github.sodarx.biliplay.core.PreferredMusic
import io.github.sodarx.biliplay.core.Track
import kotlinx.coroutines.flow.distinctUntilChanged

enum class LibrarySearchKind { Queue, Folder, Uploader }

data class LibrarySearchState(
    val kind: LibrarySearchKind,
    val sourceId: Long,
    val session: Long,
    val query: String = "",
    val tracks: List<Track> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null,
)

@Composable
internal fun LibrarySearchInput(search: LibrarySearchState, m: BiliPlayViewModel) {
    val text = rememberTextFieldState(search.query)
    val searchBar = rememberSearchBarState(initialValue = SearchBarValue.Expanded)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(search.session) { focus.requestFocus() }
    LaunchedEffect(search.query) {
        if (text.text.toString() != search.query) text.edit { replace(0, length, search.query) }
    }
    LaunchedEffect(text, search.session) {
        snapshotFlow { text.text.toString() }.distinctUntilChanged().collect {
            m.librarySearchQuery(it, search.session)
        }
    }
    Surface(shape = SearchBarDefaults.inputFieldShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        SearchBarDefaults.InputField(
            textFieldState = text,
            searchBarState = searchBar,
            onSearch = {
                m.librarySearchQuery(text.text.toString(), search.session)
                m.loadLibrarySearch()
                keyboard?.hide()
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text(when (search.kind) {
                LibrarySearchKind.Queue -> "搜索播放列表"
                LibrarySearchKind.Folder -> "搜索此收藏夹"
                LibrarySearchKind.Uploader -> "搜索此空间的投稿"
            }, maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (text.text.isNotEmpty()) IconButton(onClick = { text.edit { replace(0, length, "") } }) {
                    Icon(Icons.Default.Close, "清除搜索")
                }
            },
        )
    }
}

@Composable
internal fun LibrarySearchScreen(s: AppState, m: BiliPlayViewModel) {
    val search = s.librarySearch ?: return
    LaunchedEffect(search.session, s.routeVisit) {
        if (!search.searched && search.error == null) m.loadLibrarySearch()
    }
    val indices = if (search.kind == LibrarySearchKind.Queue && search.query.isNotBlank())
        s.queue.indices.filter { PreferredMusic.matches(s.queue[it], search.query) } else emptyList()
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f)) {
            if (search.kind == LibrarySearchKind.Queue) {
                items(indices, key = { it }) { index ->
                    FeatureTrackRow(s.queue[index], m, onPlay = { m.playFromQueue(s.queue[index], index) })
                }
            } else {
                items(search.tracks, key = { it.key }) { track ->
                    FeatureTrackRow(track, m, onPlay = { m.play(track, search.tracks) })
                }
            }
            when {
                search.loading -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        CircularWavyProgressIndicator()
                    }
                }
                search.error != null -> item { EmptyContent(search.error, { m.loadLibrarySearch(more = search.page > 0) }) }
                search.kind == LibrarySearchKind.Queue && indices.isEmpty() -> Unit
                search.searched && search.tracks.isEmpty() -> Unit
                search.hasMore -> item {
                    LibrarySearchMore(search, m)
                }
            }
        }
    }
}

@Composable
private fun LibrarySearchMore(search: LibrarySearchState, m: BiliPlayViewModel) {
    val view = LocalView.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(visible, search.session, search.query, search.page) {
        if (visible) m.loadLibrarySearch(more = true)
    }
    Spacer(Modifier.fillMaxWidth().height(48.dp).onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = bounds.height > 0 && bounds.bottom > 0 && bounds.top < view.height
    })
}
