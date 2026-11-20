@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sodarx.biliplay.core.SleepTimer
import kotlinx.coroutines.delay

/** Player top-bar timer button; an active timer becomes a compact capsule with remaining time. */
@Composable
internal fun SleepTimerAction(endsAt: Long, onClick: () -> Unit) {
    val remaining by
        produceState(
            initialValue = SleepTimer.remaining(endsAt, SystemClock.elapsedRealtime()),
            key1 = endsAt,
        ) {
            while (true) {
                value = SleepTimer.remaining(endsAt, SystemClock.elapsedRealtime())
                if (value <= 0L) break
                delay(500)
            }
        }
    if (endsAt > 0L && remaining > 0L) {
        Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
            Surface(
                onClick = onClick,
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Default.Timer, null, Modifier.size(16.dp))
                    Text(SleepTimer.label(remaining), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    } else {
        IconButton(onClick = onClick) { Icon(Icons.Default.Timer, "定时关闭") }
    }
}

@Composable
internal fun SleepTimerDialog(s: AppState, m: BiliPlayViewModel) {
    var custom by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf("") }
    val active = s.sleepTimerEndsAt > 0L
    val remaining by
        produceState(
            initialValue = SleepTimer.remaining(s.sleepTimerEndsAt, SystemClock.elapsedRealtime()),
            key1 = s.sleepTimerEndsAt,
        ) {
            while (true) {
                value = SleepTimer.remaining(s.sleepTimerEndsAt, SystemClock.elapsedRealtime())
                if (value <= 0L) break
                delay(500)
            }
        }
    val customMinutes = SleepTimer.parseMinutes(minutes)
    AlertDialog(
        onDismissRequest = m::dismissDialog,
        title = { Text("定时关闭") },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (active && remaining > 0L)
                    Text(
                        "剩余 ${SleepTimer.label(remaining)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                if (custom) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { input ->
                            minutes = input.filter(Char::isDigit).take(3)
                        },
                        label = { Text("分钟（1-${SleepTimer.MAX_MINUTES}）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                } else {
                    TimerOption("不开启", !active) {
                        m.sleepTimer(null)
                        m.dismissDialog()
                    }
                    SleepTimer.presets.forEach { preset ->
                        TimerOption(
                            "$preset 分钟",
                            active && remaining > 0L && dotMinutes(remaining) == preset,
                        ) {
                            m.sleepTimer(preset)
                            m.dismissDialog()
                        }
                    }
                    TimerOption("自定义…", false) {
                        custom = true
                        if (minutes.isBlank() && active) minutes = dotMinutes(remaining).toString()
                    }
                }
            }
        },
        confirmButton = {
            if (custom)
                TextButton(
                    onClick = {
                        customMinutes?.let {
                            m.sleepTimer(it)
                            m.dismissDialog()
                        }
                    },
                    enabled = customMinutes != null,
                ) { Text("开始") }
        },
        dismissButton = {
            if (custom)
                TextButton(onClick = { custom = false }) { Text("返回") }
            else TextButton(onClick = m::dismissDialog) { Text("关闭") }
        },
    )
}

@Composable
private fun TimerOption(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        onClick = onClick,
        content = { Text(label) },
        leadingContent = { RadioButton(selected, { onClick() }) },
    )
}

private fun dotMinutes(remainingMillis: Long): Int =
    ((remainingMillis.coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()
