@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sodarx.biliplay.core.*
import io.github.sodarx.biliplay.core.Track

@Composable
internal fun Home(state: AppState, model: BiliPlayViewModel) {
    val grid = rememberLazyStaggeredGridState()
    var handledHomeAction by rememberSaveable { mutableLongStateOf(state.homeActionId) }
    LaunchedEffect(state.homeActionId) {
        if (handledHomeAction == state.homeActionId) return@LaunchedEffect
        handledHomeAction = state.homeActionId
        when (
            HomeTabAction.choose(
                grid.canScrollBackward,
                state.homeRefreshing,
            )
        ) {
            HomeTabAction.ScrollToTop -> grid.animateScrollToItem(0)
            HomeTabAction.Refresh -> model.refreshHomeFromGesture()
            HomeTabAction.None -> Unit
        }
    }
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(Unit) { model.homeVisible() }
    PullToRefreshBox(
        state = pullState,
        indicator = {
            AnimatedVisibility(
                state.homeRefreshing || pullState.distanceFraction > 0f,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                enter = fadeIn() + scaleIn(initialScale = .8f),
                exit = fadeOut(),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 4.dp,
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (state.homeRefreshing)
                            CircularWavyProgressIndicator(Modifier.size(24.dp))
                        else
                            Icon(
                                Icons.Default.Refresh,
                                "下拉刷新全部内容",
                                Modifier.graphicsLayer {
                                    rotationZ = pullState.distanceFraction * 180f
                                },
                            )
                    }
                }
            }
        },
        isRefreshing = state.homeRefreshing,
        onRefresh = model::refreshHomeFromGesture,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalStaggeredGrid(
            state = grid,
            columns = StaggeredGridCells.Fixed(2),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp + LocalPlayerContentBottom.current,
                ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalItemSpacing = 16.dp,
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                AnimatedVisibility(
                    state.showContinueListening &&
                        state.current != null &&
                        state.playbackOrigin != null,
                    enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                    exit =
                        fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                            shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
                ) {
                    val current = state.current
                    if (current != null)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("继续收听", style = MaterialTheme.typography.titleLarge)
                            ExpressiveCard(
                                onClick = model::resumeListening,
                                onLongClick = { model.dialog("trackActions", current) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                ListItem(
                                    content = {
                                        Text(
                                            state.playbackOrigin?.title.orEmpty(),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        val index =
                                            state.queue.indexOfFirst { it.key == current.key }
                                        Text(
                                            "继续进度播放 · ${index + 1}/${state.queue.size} · ${state.position / 60000}:${(state.position / 1000 % 60).toString().padStart(2, '0')}\n${songTitle(current.title)}",
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingContent = { Artwork(current, 56) },
                                    trailingContent = {
                                        FilledIconButton(onClick = model::resumeListening) {
                                            MotionPlayIcon(state.playing)
                                        }
                                    },
                                )
                            }
                        }
                }
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("猜你喜欢", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(
                        onClick = model::refreshLiked,
                        enabled = !state.likedRefreshing && !state.homeRefreshing,
                    ) {
                        if (state.likedRefreshing)
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        else Icon(Icons.Default.Refresh, "换三首猜你喜欢")
                    }
                }
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                Column { state.likedMusic.forEach { FeatureTrackRow(it, model) } }
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("今日推荐", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    FilledTonalButton(
                        onClick = { model.playAll(state.dailyMusic) },
                        enabled = state.dailyMusic.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("立即播放", maxLines = 1)
                    }
                    IconButton(
                        onClick = model::refreshDaily,
                        enabled = !state.dailyRefreshing && !state.homeRefreshing,
                    ) {
                        if (state.dailyRefreshing)
                            CircularWavyProgressIndicator(Modifier.size(24.dp))
                        else Icon(Icons.Default.Refresh, "刷新今日推荐")
                    }
                }
            }
            items(state.dailyMusic, key = { "daily:${it.key}" }) { track ->
                HomeMusicCard(
                    track,
                    model,
                    Modifier,
                )
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                Text("飙升热榜", style = MaterialTheme.typography.titleLarge)
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                Column {
                    if (state.risingMusic.isEmpty() && !state.homeRefreshing)
                        EmptyContent(state.chartError ?: "音乐热榜暂不可用", model::refreshHomeFromGesture)
                    state.risingMusic.forEachIndexed { index, track ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}",
                                Modifier.width(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Box(Modifier.weight(1f)) { FeatureTrackRow(track, model) }
                        }
                    }
                }
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                Text("更多推荐", style = MaterialTheme.typography.titleLarge)
            }
            items(state.recommendations, key = { "more:${it.key}" }) { track ->
                HomeMusicCard(
                    track,
                    model,
                    Modifier,
                )
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                LoadMore(state.homeHasMore, state, model)
            }
            if (state.recommendations.isEmpty() && !state.homeRefreshing)
                item(span = StaggeredGridItemSpan.FullLine) {
                    EmptyContent(state.pageError ?: "暂无更多推荐", model::refreshHomeFromGesture)
                }
        }
    }
}

@Composable
private fun HomeMusicCard(track: Track, model: BiliPlayViewModel, modifier: Modifier) {
    ExpressiveCard(
        onClick = { model.play(track) },
        onLongClick = { model.dialog("trackActions", track) },
        modifier = modifier,
        selected = LocalNowPlaying.current == track.key,
    ) {
        SongArtwork(track, Modifier.fillMaxWidth())
        Column(Modifier.padding(12.dp)) {
            Text(
                songTitle(track.title),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                track.uploader,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
