@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.sodarx.biliplay.core.*
import kotlinx.coroutines.launch

@Composable
internal fun UserRow(user: Uploader, m: BiliPlayViewModel) {
    ListItem(
        onClick = { m.openUploader(user) },
        modifier = Modifier,
        content = { Text(user.name) },
        supportingContent = { Text("UID ${user.id}") },
        leadingContent = {
            AsyncImage(
                user.avatar,
                null,
                Modifier.size(48.dp).clip(MaterialTheme.shapes.extraLarge),
            )
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
}

@Composable
internal fun UploaderScreen(s: AppState, m: BiliPlayViewModel) {
    LazyColumn {
        item {
            val u = s.selectedUploader ?: return@item
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AsyncImage(
                    u.avatar,
                    "${u.name}头像",
                    Modifier.size(88.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                Text(u.name, style = MaterialTheme.typography.headlineSmall)
                if (u.signature.isNotBlank())
                    Text(
                        u.signature,
                        maxLines = 3,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                FilledTonalButton(onClick = m::follow, enabled = !s.followingCurrentBusy) {
                    Text(if (s.isFollowing) "已关注" else "关注")
                }
            }
        }
        item { Text("全部投稿", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
        items(s.uploaderTracks, key = { it.bvid }) { FeatureTrackRow(it, m) }
        item { LoadMore(s.uploaderHasMore, s, m) }
        if (s.uploaderTracks.isEmpty() && !s.loading)
            item { EmptyContent(s.pageError ?: "暂无投稿", { m.loadUploader() }) }
    }
}

@Composable
internal fun SocialScreen(s: AppState, m: BiliPlayViewModel) {
    LazyColumn {
        items(s.socialUsers, key = { it.id }) { UserRow(it, m) }
        item { LoadMore(s.socialHasMore, s, m) }
        if (s.socialUsers.isEmpty() && !s.loading)
            item { EmptyContent(s.pageError ?: "暂无内容", { m.loadSocial() }) }
    }
}

@Composable
internal fun CommentsScreen(s: AppState, m: BiliPlayViewModel) {
    var emotesOpen by remember { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var viewportHeight by
        remember(configuration.orientation, configuration.screenWidthDp) {
            mutableFloatStateOf(configuration.screenHeightDp.toFloat())
        }
    SideEffect {
        if (configuration.screenHeightDp > viewportHeight)
            viewportHeight = configuration.screenHeightDp.toFloat()
    }
    val imeInsets = WindowInsets.ime
    var replaceKeyboard by remember { mutableStateOf(false) }
    val targetPanelHeight = EmojiPanelLayout.targetHeight(viewportHeight).dp
    val panelSpatial = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.Dp>()
    BackHandler(emotesOpen) { emotesOpen = false }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f)) {
            s.commentRoot?.takeIf { s.route == "replies" }?.let { item { CommentRow(it, s, m) } }
            val rows = if (s.route == "replies") s.replies else s.comments
            items(rows, key = { it.id }) { CommentRow(it, s, m) }
            item { LoadMore(if (s.route == "replies") s.replyHasMore else s.commentHasMore, s, m) }
            if (rows.isEmpty() && !s.loading) item { EmptyContent(s.pageError ?: "暂无评论") }
        }
        if (s.replyTarget != null)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "回复 ${s.replyTarget.author}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = { m.reply(null) }) { Icon(Icons.Default.Close, "取消回复") }
            }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    emotesOpen = !emotesOpen
                    if (emotesOpen) {
                        val currentIme = imeInsets.getBottom(density)
                        replaceKeyboard = currentIme > 0
                        focus.clearFocus()
                        keyboard?.hide()
                        m.emotes()
                    } else {
                        replaceKeyboard = false
                        inputFocus.requestFocus()
                        keyboard?.show()
                    }
                }
            ) {
                Icon(
                    if (emotesOpen) Icons.Default.Keyboard else Icons.Default.EmojiEmotions,
                    if (emotesOpen) "键盘" else "表情",
                )
            }
            OutlinedTextField(
                value = s.commentDraft,
                onValueChange = m::commentDraft,
                modifier =
                    Modifier.weight(1f).focusRequester(inputFocus).onFocusChanged {
                        if (it.isFocused) emotesOpen = false
                    },
                placeholder = { Text("评论") },
                maxLines = 4,
            )
            IconButton(
                onClick = m::sendComment,
                enabled = !s.posting && s.commentDraft.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送评论")
            }
        }
        AnimatedVisibility(emotesOpen, enter = fadeIn(), exit = fadeOut()) {
            val reservation =
                transition.animateDp(
                    transitionSpec = {
                        if (replaceKeyboard && targetState == EnterExitState.Visible) snap()
                        else panelSpatial
                    },
                    label = "composer input reservation",
                ) { phase ->
                    if (phase == EnterExitState.Visible) targetPanelHeight else 0.dp
                }
            ComposerEmotes(
                s,
                m,
                Modifier.fillMaxWidth().layout { measurable, constraints ->
                    val height =
                        EmojiPanelLayout.remaining(
                            reservation.value.roundToPx(),
                            imeInsets.getBottom(density),
                            constraints.maxHeight,
                        )
                    val child =
                        measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                    layout(child.width, height) { child.placeRelative(0, 0) }
                },
            )
        }
    }
}

@Composable
private fun CommentRow(c: Comment, s: AppState, m: BiliPlayViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(c.avatar, null, Modifier.size(36.dp).clip(MaterialTheme.shapes.extraLarge))
            Column {
                Text(c.author, style = MaterialTheme.typography.titleSmall)
                Text(
                    date(c.timestamp) + (if (c.pinned) " · 置顶" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SelectionContainer { Text(c.message) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { m.react(c, false) }) {
                Icon(
                    if (c.reaction == 1) Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt,
                    null,
                    Modifier.size(18.dp),
                )
                Text(" ${c.likes}")
            }
            IconButton(onClick = { m.react(c, true) }) {
                Icon(
                    if (c.reaction == 2) Icons.Default.ThumbDown else Icons.Default.ThumbDownOffAlt,
                    "点踩",
                    Modifier.size(18.dp),
                )
            }
            TextButton(onClick = { m.reply(c) }) { Text("回复") }
            if (c.replies > 0 && s.route != "replies")
                TextButton(onClick = { m.openReplies(c) }) {
                    Text("展开${c.replies}条回复")
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
                }
        }
    }
    HorizontalDivider()
}

@Composable
internal fun NotificationsScreen(s: AppState, m: BiliPlayViewModel) {
    Column {
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("私信", "点赞", "回复与 @", "系统通知").forEach { category ->
                FilterChip(
                    s.notificationCategory == category,
                    { m.notificationCategory(category) },
                    label = { Text(category) },
                )
            }
        }
        s.notificationWarning?.let {
            Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        }
        LazyColumn {
            items(s.notifications, key = { it.id }) { n ->
                ListItem(
                    onClick = { m.openNotification(n) },
                    content = { Text(n.title.ifBlank { n.category }) },
                    supportingContent = {
                        Column {
                            if (n.content.isNotBlank())
                                Text(n.content, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (n.target.isNotBlank()) Text(n.target, maxLines = 2)
                            Text(date(n.timestamp), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    leadingContent = {
                        if (n.avatar.isNotBlank())
                            AsyncImage(
                                n.avatar,
                                null,
                                Modifier.size(40.dp).clip(MaterialTheme.shapes.extraLarge),
                            )
                    },
                )
            }
            item { LoadMore(s.notificationHasMore, s, m) }
            if (s.notifications.isEmpty() && !s.loading)
                item {
                    EmptyContent(
                        if (s.notificationWarning != null) "读取未完成" else "暂无通知",
                        { m.loadNotifications() },
                    )
                }
        }
    }
}

@Composable
internal fun CommentSheet(state: AppState, model: BiliPlayViewModel, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val latest by rememberUpdatedState(state)
    LaunchedEffect(state.route) {
        focus.clearFocus()
        keyboard?.hide()
    }

    val sheet =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    val commentEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    ModalBottomSheet(onDismissRequest = model::dismissComments, sheetState = sheet) {
        BackHandler(state.route == "replies") { model.back() }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.route == "replies")
                    IconButton(onClick = model::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回评论")
                    }
                Text(
                    if (state.route == "replies") "回复" else "评论",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (state.route == "comments") {
                    Spacer(Modifier.width(12.dp))
                    FilterChip(
                        !state.commentLatest,
                        { model.commentSort(false) },
                        label = { Text("热门") },
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        state.commentLatest,
                        { model.commentSort(true) },
                        label = { Text("最新") },
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        scope.launch {
                            sheet.hide()
                            model.dismissComments()
                        }
                    }
                ) {
                    Icon(Icons.Default.Close, "关闭评论")
                }
            }
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    state.route,
                    transitionSpec = {
                        val spatial =
                            androidx.compose.animation.core.spring<
                                androidx.compose.ui.unit.IntOffset
                            >(
                                dampingRatio = .76f,
                                stiffness = 380f,
                            )
                        if (targetState == "comments")
                            (slideInHorizontally(spatial) { -it / 5 } +
                                fadeIn(commentEffects)) togetherWith
                                (slideOutHorizontally(spatial) { it / 3 } + fadeOut(commentEffects))
                        else
                            (slideInHorizontally(spatial) { it / 3 } +
                                fadeIn(commentEffects)) togetherWith
                                (slideOutHorizontally(spatial) { -it / 5 } +
                                    fadeOut(commentEffects))
                    },
                    label = "comment replies",
                ) { route ->
                    CommentsScreen(latest.copy(route = route), model)
                }
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)) {
                    ExpressiveSnackbarHost(snackbar)
                }
            }
        }
    }
}
