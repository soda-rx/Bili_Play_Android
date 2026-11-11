package io.github.sodarx.biliplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun NotificationDetailScreen(s: AppState) {
    val notice = s.selectedNotification ?: return
    SelectionContainer {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (notice.timestamp > 0)
                Text(
                    date(notice.timestamp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (notice.content.isNotBlank()) Text(notice.content)
            if (notice.target.isNotBlank() && notice.target != notice.content) Text(notice.target)
            if (notice.content.isBlank() && notice.target.isBlank()) Text(notice.title)
        }
    }
}
