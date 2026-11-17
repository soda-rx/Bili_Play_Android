@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.sodarx.biliplay.core.DirectMessage
import io.github.sodarx.biliplay.core.Notification
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private sealed interface ChatRow {
    val key: String

    data class Separator(val text: String, override val key: String) : ChatRow

    data class Message(
        val message: DirectMessage,
        val showName: Boolean,
        override val key: String,
    ) : ChatRow
}

/** 群聊式排版：时间居中分隔，间隔五分钟以上才插入新的时间行。 */
private fun conversationRows(messages: List<DirectMessage>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var previous: DirectMessage? = null
    for (message in messages.sortedBy { it.sequence }) {
        val gap = previous?.let { message.timestamp - it.timestamp } ?: Long.MAX_VALUE
        val newDay =
            previous == null ||
                dayKey(previous.timestamp) != dayKey(message.timestamp)
        if (previous == null || newDay || gap >= 300) {
            rows += ChatRow.Separator(chatTimeLabel(message.timestamp), "sep:${message.id}")
        }
        val showName =
            !message.own &&
                (previous == null ||
                    previous.own ||
                    previous.sender != message.sender ||
                    gap >= 300)
        rows += ChatRow.Message(message, showName, "msg:${message.id}")
        previous = message
    }
    return rows
}

private fun dayKey(timestamp: Long): Long =
    if (timestamp <= 0) Long.MIN_VALUE
    else
        Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

private fun chatTimeLabel(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochSecond(timestamp).atZone(zone)
    val now = ZonedDateTime.now(zone)
    val clock = DateTimeFormatter.ofPattern("HH:mm").format(time)
    val days = ChronoUnit.DAYS.between(time.toLocalDate(), now.toLocalDate())
    return when {
        days == 0L -> "今天 $clock"
        days == 1L -> "昨天 $clock"
        days in 2L..6L -> "${weekdayLabel(time.dayOfWeek.value)} $clock"
        time.year == now.year -> "${time.monthValue}月${time.dayOfMonth}日 $clock"
        else -> "${time.year}年${time.monthValue}月${time.dayOfMonth}日 $clock"
    }
}

private fun weekdayLabel(value: Int) =
    when (value) {
        1 -> "星期一"
        2 -> "星期二"
        3 -> "星期三"
        4 -> "星期四"
        5 -> "星期五"
        6 -> "星期六"
        else -> "星期日"
    }

@Composable
internal fun ConversationScreen(s: AppState, m: BiliPlayViewModel) {
    val peer = s.conversation ?: return
    val rows = remember(s.conversationMessages) { conversationRows(s.conversationMessages) }
    var preview by remember(s.conversationMessages) { mutableStateOf<String?>(null) }
    var emojiOpen by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(s.conversationMessages.size) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)) {
            if (s.conversationMessages.isEmpty() && !s.loading) {
                EmptyContent(s.pageError ?: "暂无聊天记录", { m.loadConversation() })
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(rows.asReversed(), key = { it.key }) { row ->
                        when (row) {
                            is ChatRow.Separator -> TimeSeparator(row.text)
                            is ChatRow.Message ->
                                MessageRow(row, peer, s, onPicture = { preview = it })
                        }
                    }
                    item { LoadMore(s.conversationHasMore, s, m) }
                }
            }
        }
        ConversationComposer(
            s,
            m,
            emojiOpen = emojiOpen,
            onEmojiOpenChange = { emojiOpen = it },
        )
        AnimatedVisibility(emojiOpen, enter = fadeIn(), exit = fadeOut()) {
            EmojiPanel { m.appendConversationText(it) }
        }
    }
    BackHandler(emojiOpen) { emojiOpen = false }
    preview?.let { PictureViewer(listOf(it), 0, onClose = { preview = null }) }
}

@Composable
private fun TimeSeparator(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatAvatar(source: String, name: String) {
    Surface(
        Modifier.size(36.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        if (source.isBlank())
            Box(contentAlignment = Alignment.Center) {
                Text(
                    name.take(1),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        else
            AsyncImage(
                source,
                "${name}头像",
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop,
            )
    }
}

@Composable
private fun MessageRow(
    row: ChatRow.Message,
    peer: Notification,
    s: AppState,
    onPicture: (String) -> Unit,
) {
    val message = row.message
    val own = message.own
    val bubbleColor =
        if (own) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (own) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val linkColor =
        if (own) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val avatar = if (own) s.account?.avatar.orEmpty() else peer.avatar
    val name = if (own) s.account?.name ?: "我" else peer.title
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!own) {
            ChatAvatar(avatar, name)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            Modifier.widthIn(max = 296.dp),
            horizontalAlignment = if (own) Alignment.End else Alignment.Start,
        ) {
            if (!own && row.showName)
                Text(
                    name,
                    Modifier.padding(start = 6.dp, bottom = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Surface(color = bubbleColor, shape = MaterialTheme.shapes.large) {
                Column(
                    Modifier.padding(
                        horizontal = if (message.image.isNotBlank()) 4.dp else 14.dp,
                        vertical = if (message.image.isNotBlank()) 4.dp else 9.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (message.image.isNotBlank())
                        AsyncImage(
                            message.image,
                            "聊天图片",
                            Modifier.widthIn(max = 240.dp)
                                .heightIn(max = 280.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .clickable(onClickLabel = "查看聊天图片") { onPicture(message.image) },
                            contentScale = ContentScale.Fit,
                        )
                    if (message.text.isNotBlank())
                        Text(
                            chatRichText(message.text, linkColor),
                            color = contentColor,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                }
            }
        }
        if (own) {
            Spacer(Modifier.width(8.dp))
            ChatAvatar(avatar, name)
        }
    }
}

@Composable
private fun chatRichText(text: String, linkColor: Color): AnnotatedString {
    return remember(text, linkColor) {
        buildAnnotatedString {
            val pattern = Regex("https?://[^\\s\\u4e00-\\u9fff]+")
            var index = 0
            for (match in pattern.findAll(text)) {
                if (match.range.first > index) append(text.substring(index, match.range.first))
                val url = match.value.trimEnd('.', ',', '。', '，', ')', '）')
                withLink(
                    LinkAnnotation.Url(
                        url,
                        TextLinkStyles(
                            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                        ),
                    )
                ) {
                    append(url)
                }
                index = match.range.first + url.length
            }
            if (index < text.length) append(text.substring(index))
        }
    }
}

private val chatEmoji =
    listOf(
        "😀", "😄", "😁", "😆", "😅", "😂", "🙂", "😉", "😊", "😍", "🥰", "😘",
        "😜", "😝", "🤪", "🤗", "🤔", "🤨", "😐", "😴", "😌", "😔", "😭", "😢",
        "😤", "😠", "🤯", "🥵", "🥶", "😱", "😮", "🙄", "😏", "🤫", "🤭", "😬",
        "👍", "👎", "👏", "🙏", "💪", "👌", "✌️", "🤝", "❤️", "💔", "💯", "🔥",
        "🎵", "🎶", "🎧", "🎤", "⭐", "✨", "🌙", "☀️", "🌈", "🍀", "🎉", "🎁",
        "🐱", "🐶", "🐼", "🍋", "🍉", "☕", "🍜", "🍰", "🚀", "👀", "🤡", "💤",
    )

@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().heightIn(max = 260.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(chatEmoji) { emoji ->
                Text(
                    emoji,
                    Modifier.clickable(onClickLabel = "插入表情 $emoji") { onPick(emoji) }
                        .padding(8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
    }
}

@Composable
private fun ConversationComposer(
    s: AppState,
    m: BiliPlayViewModel,
    emojiOpen: Boolean,
    onEmojiOpenChange: (Boolean) -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) m.setConversationImage(uri)
        }
    val canSend =
        !s.conversationSending &&
            (s.conversationDraft.isNotBlank() || s.conversationImageReady)
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            if (s.conversationImage.isNotBlank())
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(56.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        AsyncImage(
                            pictureRequest(s.conversationImage, 256),
                            "待发送图片",
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (s.conversationImageReady) "发送前压缩并移除位置等元数据" else "正在处理图片…",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = m::removeConversationImage, enabled = !s.conversationSending) {
                        Icon(Icons.Default.Close, "移除待发送图片")
                    }
                }
            BasicTextField(
                value = s.conversationDraft,
                onValueChange = m::conversationDraft,
                enabled = !s.conversationSending,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .heightIn(min = 34.dp, max = 104.dp)
                        .focusRequester(inputFocus)
                        .onFocusChanged {
                            if (it.isFocused && emojiOpen) onEmojiOpenChange(false)
                        },
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                maxLines = 4,
                decorationBox = { inner ->
                    Box {
                        if (s.conversationDraft.isEmpty())
                            Text(
                                "发送消息…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        inner()
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        val open = !emojiOpen
                        onEmojiOpenChange(open)
                        if (open) {
                            focus.clearFocus()
                            keyboard?.hide()
                        } else {
                            inputFocus.requestFocus()
                            keyboard?.show()
                        }
                    }
                ) {
                    Icon(
                        if (emojiOpen) Icons.Default.Keyboard else Icons.Default.EmojiEmotions,
                        if (emojiOpen) "键盘" else "表情",
                    )
                }
                IconButton(
                    enabled = !s.conversationSending,
                    onClick = {
                        onEmojiOpenChange(false)
                        runCatching {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                            .onFailure { m.message("无法打开系统照片选择器") }
                    },
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, "发送图片")
                }
                Spacer(Modifier.weight(1f))
                if (s.conversationSending)
                    CircularWavyProgressIndicator(Modifier.size(24.dp))
                Button(
                    onClick = m::sendConversationMessage,
                    enabled = canSend,
                    shape = MaterialTheme.shapes.extraLarge,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text("发送")
                }
            }
        }
    }
}
