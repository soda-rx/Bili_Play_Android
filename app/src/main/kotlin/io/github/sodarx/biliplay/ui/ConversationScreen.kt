@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
internal fun ConversationScreen(s: AppState, m: BiliPlayViewModel) {
    val peer = s.conversation ?: return
    if (s.conversationMessages.isEmpty() && !s.loading) {
        EmptyContent(s.pageError ?: "暂无聊天记录", { m.loadConversation() })
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(s.conversationMessages.asReversed(), key = { it.id }) { message ->
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = if (message.own) Alignment.End else Alignment.Start,
            ) {
                if (message.timestamp > 0)
                    Text(
                        date(message.timestamp),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.own) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.Top,
                ) {
                    if (!message.own && peer.avatar.isNotBlank()) {
                        AsyncImage(
                            peer.avatar,
                            null,
                            Modifier.size(32.dp).clip(MaterialTheme.shapes.large),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Surface(
                        Modifier.widthIn(max = 280.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color =
                            if (message.own) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (message.image.isNotBlank())
                                AsyncImage(
                                    message.image,
                                    "图片消息",
                                    Modifier.fillMaxWidth()
                                        .heightIn(min = 80.dp, max = 240.dp)
                                        .clip(MaterialTheme.shapes.large),
                                    contentScale = ContentScale.Fit,
                                )
                            if (message.text.isNotBlank()) SelectionContainer { Text(message.text) }
                        }
                    }
                }
            }
        }
        item { LoadMore(s.conversationHasMore, s, m) }
    }
}
