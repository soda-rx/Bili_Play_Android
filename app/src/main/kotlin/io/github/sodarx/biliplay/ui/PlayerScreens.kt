@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sodarx.biliplay.core.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Composable
internal fun QueueScreen(s: AppState, m: BiliPlayViewModel) {
    if (s.queue.isEmpty()) EmptyContent("播放列表为空") else ReorderableQueue(s, m)
}

@Composable
internal fun LyricsScreen(s: AppState, m: BiliPlayViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val lyricsHeight = maxHeight
        val list = rememberLazyListState()
        val lyricHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        val dragging by list.interactionSource.collectIsDraggedAsState()
        val activeFlow =
            remember(m, s.lyrics, s.current?.key, s.profileId, s.position) {
                m.playbackProgress
                    .map { p ->
                        val position =
                            if (p.profileId == s.profileId && p.current?.key == s.current?.key)
                                p.position
                            else s.position
                        LyricLayout.active(s.lyrics, position)
                    }
                    .distinctUntilChanged()
            }
        val active by
            activeFlow.collectAsStateWithLifecycle(
                initialValue = LyricLayout.active(s.lyrics, s.position)
            )
        val viewportHeight by
            remember(list) { derivedStateOf { list.layoutInfo.viewportSize.height } }
        LaunchedEffect(dragging, s.current?.key) {
            if (dragging) m.followLyrics(false)
            else if (!s.followLyrics) {
                snapshotFlow { list.isScrollInProgress }.first { !it }
                kotlinx.coroutines.delay(3000)
                m.followLyrics(true)
            }
        }
        val lyricSpring = spring<Float>(dampingRatio = .72f, stiffness = 188f)
        var scrollVelocity by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(active, s.followLyrics, viewportHeight) {
            if (s.followLyrics && active >= 0) {
                if (list.layoutInfo.visibleItemsInfo.none { it.index == active })
                    list.scrollToItem(
                        active,
                        -(list.layoutInfo.viewportSize.height * .22f).toInt(),
                    )
                val item =
                    snapshotFlow {
                        list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
                    }
                        .first { it != null }!!
                val top = LyricLayout.targetTop(list.layoutInfo.viewportSize.height, item.size)
                val distance = (item.offset - top).toFloat()
                list.scroll {
                    var previous = 0f
                    animate(
                        0f,
                        distance,
                        initialVelocity = scrollVelocity.coerceIn(-2200f, 2200f),
                        animationSpec = lyricSpring,
                    ) { value, velocity ->
                        scrollBy(value - previous)
                        previous = value
                        scrollVelocity = velocity
                    }
                    scrollVelocity = 0f
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (s.lyrics.isEmpty()) EmptyContent("没有可用字幕")
            else
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).lyricEdgeFade(),
                    contentPadding =
                        PaddingValues(
                            start = 24.dp,
                            end = 24.dp,
                            top = (lyricsHeight * .27f).coerceAtLeast(24.dp),
                            bottom = (lyricsHeight * .73f).coerceAtLeast(64.dp),
                        ),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(s.lyrics.size, key = { i -> "${s.current?.key}:$i" }) { i ->
                        val current = i == active
                        val scale by
                            animateFloatAsState(
                                if (current) 1.13f else .95f,
                                spring(dampingRatio = .62f, stiffness = 204f),
                                label = "lyric elastic scale",
                            )
                        val offset by
                            animateDpAsState(
                                if (current) 6.dp else 0.dp,
                                spring(dampingRatio = .68f, stiffness = 190f),
                                label = "lyric elastic offset",
                            )
                        val ripple = remember { Animatable(0f) }
                        LaunchedEffect(active) {
                            val distance = kotlin.math.abs(i - active)
                            if (distance > 3) {
                                ripple.snapTo(0f)
                                return@LaunchedEffect
                            }
                            val motionScale =
                                kotlinx.coroutines
                                    .currentCoroutineContext()[
                                        androidx.compose.ui.MotionDurationScale]
                                    ?.scaleFactor ?: 1f
                            if (motionScale <= 0f) {
                                ripple.snapTo(0f)
                                return@LaunchedEffect
                            }
                            kotlinx.coroutines.delay((distance * 18L * motionScale).toLong())
                            ripple.snapTo((6 - distance).toFloat())
                            ripple.animateTo(0f, spring(dampingRatio = .68f, stiffness = 190f))
                        }
                        androidx.compose.foundation.text.BasicText(
                            s.lyrics[i].content,
                            style =
                                MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 32.sp,
                                    lineHeight = 1.375.em,
                                    fontWeight = FontWeight.SemiBold,
                                    color =
                                        if (current) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = .42f),
                                ),
                            autoSize =
                                androidx.compose.foundation.text.TextAutoSize.StepBased(
                                    minFontSize = 18.sp,
                                    maxFontSize = 32.sp,
                                    stepSize = 1.sp,
                                ),
                            modifier =
                                Modifier.fillMaxWidth(1f / 1.16f)
                                    .padding(end = 8.dp)
                                    .heightIn(max = (lyricsHeight * .72f).coerceAtLeast(48.dp))
                                    .graphicsLayer {
                                        scaleX = scale.coerceIn(.92f, 1.16f)
                                        scaleY = scaleX
                                        translationX = offset.toPx()
                                        translationY = ripple.value.coerceIn(-8f, 8f)
                                        transformOrigin = TransformOrigin(0f, .5f)
                                    }
                                    .clickable {
                                        lyricHaptic.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType
                                                .TextHandleMove
                                        )
                                        m.seek((s.lyrics[i].from * 1000).toLong())
                                        m.followLyrics(true)
                                    }
                                    .padding(vertical = 18.dp),
                        )
                    }
                }
        }
    }
}

@Composable
internal fun PlayerPageContent(
    s: AppState,
    m: BiliPlayViewModel,
    sharedArtwork: Boolean,
    isCurrent: Boolean,
    onQuality: () -> Unit,
    onSpeed: () -> Unit,
) {
    if (s.current == null) return
    @Composable
    fun media(modifier: Modifier) {
        PlayerMediaPager(s, m, sharedArtwork, isCurrent, modifier)
    }
    @Composable
    fun controls(modifier: Modifier = Modifier) {
        Column(
            modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayerControls(s, m)
            PlayerQuickActions(s, m, onQuality, onSpeed)
        }
    }
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 4.dp)) {
            if (maxWidth > maxHeight && maxWidth >= 600.dp)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    media(Modifier.weight(1f).fillMaxHeight())
                    controls(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                }
            else if (maxHeight < 440.dp) {
                val compactMediaHeight = minOf(180.dp, maxHeight * .42f)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    media(Modifier.fillMaxWidth().height(compactMediaHeight))
                    controls(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                }
            } else
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    media(Modifier.weight(1f).fillMaxWidth())
                    controls()
                }
        }
    }
}

@Composable
private fun PlayerQuickActions(
    s: AppState,
    m: BiliPlayViewModel,
    onQuality: () -> Unit,
    onSpeed: () -> Unit,
) {
    Surface(
        modifier = Modifier.clip(MaterialTheme.shapes.extraLarge).playerActionBackground(),
        color = androidx.compose.ui.graphics.Color.Transparent,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .widthIn(min = maxWidth),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = onQuality, enabled = s.sources.isNotEmpty()) {
                    Icon(Icons.Default.GraphicEq, "选择音质")
                }
                IconButton(onClick = { m.dialog("queueMode") }) {
                    Icon(
                        when (s.mode) {
                            io.github.sodarx.biliplay.core.QueueMode.RepeatOne ->
                                Icons.Default.RepeatOne
                            io.github.sodarx.biliplay.core.QueueMode.Shuffle ->
                                Icons.Default.Shuffle
                            io.github.sodarx.biliplay.core.QueueMode.Sequential ->
                                Icons.AutoMirrored.Filled.PlaylistPlay
                            else -> Icons.Default.Repeat
                        },
                        s.mode.label,
                    )
                }
                IconButton(onClick = onSpeed) { Icon(Icons.Default.Speed, "选择播放速度") }
                IconButton(onClick = m::openComments) {
                    Icon(Icons.AutoMirrored.Filled.Comment, "评论")
                }
                IconButton(onClick = { s.current?.let(m::download) }) {
                    Icon(Icons.Default.Download, "下载")
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(s: AppState, m: BiliPlayViewModel) {
    val track = s.current ?: return
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            songTitle(track.title),
            Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                ),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.uploader,
                    Modifier.weight(1f, fill = false).clickable { m.openCurrentUploader() },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = m::followCurrent,
                    enabled = !s.followingCurrentBusy && track.uploaderId > 0,
                ) {
                    MotionSelectionIcon(
                        s.currentFollowing,
                        Icons.Default.Check,
                        Icons.Default.Add,
                        if (s.currentFollowing) "取消关注 UP 主" else "关注 UP 主",
                    )
                }
            }
            IconButton(onClick = m::openCoin) { BiliCoinIcon() }
            IconButton(onClick = { m.chooseFavorite(track) }) {
                MotionSelectionIcon(
                    s.favorite,
                    Icons.Default.Favorite,
                    Icons.Default.FavoriteBorder,
                    if (s.favorite) "取消收藏" else "收藏",
                )
            }
        }
        PlayerTimeline(s, m)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { m.seekRelative(-10000) }) {
                Icon(Icons.Default.Replay10, "快退十秒")
            }
            IconButton(onClick = { m.next(-1) }) { Icon(Icons.Default.SkipPrevious, "上一首") }
            ExpressivePlayButton(s.playing, s.buffering, m::toggle)
            IconButton(onClick = { m.next() }) { Icon(Icons.Default.SkipNext, "下一首") }
            IconButton(onClick = { m.seekRelative(10000) }) {
                Icon(Icons.Default.Forward10, "快进十秒")
            }
        }
    }
}

@Composable
private fun PlayerTimeline(s: AppState, m: BiliPlayViewModel) {
    val progress by m.playbackProgress.collectAsStateWithLifecycle()
    val matches = progress.profileId == s.profileId && progress.current?.key == s.current?.key
    val position = if (matches) progress.position else s.position
    val duration = if (matches) progress.duration else s.duration
    var dragging by remember(s.current?.key) { mutableStateOf<Float?>(null) }
    Column {
        WaveSeekBar(
            position = position,
            duration = duration,
            playing = s.playing,
            onPreview = { dragging = it },
            onSeek = {
                m.seek(it)
                dragging = null
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                time((dragging?.toLong() ?: position) / 1000),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            )
            Text(
                time(duration / 1000),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}

private fun time(seconds: Long) = "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
