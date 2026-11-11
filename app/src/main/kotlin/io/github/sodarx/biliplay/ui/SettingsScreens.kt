@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.sodarx.biliplay.BuildConfig
import io.github.sodarx.biliplay.core.*
import io.github.sodarx.biliplay.ui.theme.*
import kotlinx.coroutines.flow.first

@Composable
internal fun FeatureMine(s: AppState, m: BiliPlayViewModel) {
    LazyColumn(
        contentPadding =
            PaddingValues(top = 16.dp, bottom = 16.dp + LocalPlayerContentBottom.current)
    ) {
        item {
            ListItem(
                onClick = { if (s.account == null) m.startLogin() else m.navigate("profiles") },
                content = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            s.account?.name ?: "尚未登录",
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        s.account
                            ?.level
                            ?.takeIf { it > 0 }
                            ?.let { level ->
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        "Lv.$level",
                                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style =
                                            MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 11.sp,
                                                lineHeight = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                    )
                                }
                            }
                    }
                },
                supportingContent = { Text(s.account?.let { "UID ${it.id}" } ?: "登录") },
                leadingContent = {
                    if (s.account == null)
                        Icon(Icons.Default.AccountCircle, null, Modifier.size(64.dp))
                    else
                        Box(Modifier.size(64.dp)) {
                            AsyncImage(
                                s.account.avatar,
                                null,
                                Modifier.fillMaxSize().clip(MaterialTheme.shapes.extraLarge),
                            )
                            if (s.account.vip)
                                Surface(
                                    Modifier.align(Alignment.BottomEnd),
                                    shape = RoundedCornerShape(4.dp),
                                    color = androidx.compose.ui.graphics.Color(0xfffb7299),
                                    contentColor = androidx.compose.ui.graphics.Color.White,
                                ) {
                                    Text(
                                        "大会员",
                                        Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        fontSize = 10.sp,
                                        lineHeight = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                }
                        }
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
        }
        if (s.account != null)
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(onClick = { m.social(false) }) {
                        Text("关注 ${s.followingCount ?: "…"}")
                    }
                    TextButton(onClick = { m.social(true) }) {
                        Text("粉丝 ${s.followersCount ?: "…"}")
                    }
                }
            }
        item {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val entry = Modifier.heightIn(min = 56.dp)
                FeatureEntry(
                    "下载",
                    Icons.Default.Download,
                    s.downloads
                        .count { it.status == "complete" }
                        .takeIf { it > 0 }
                        ?.let { "$it 首" },
                    modifier = entry,
                    inlineValue = true,
                    onClick = m::openDownloads,
                )
                FeatureEntry(
                    "历史记录",
                    Icons.Default.History,
                    modifier = entry,
                    inlineValue = true,
                    onClick = { m.navigate("history") },
                )
                FeatureEntry(
                    "设置",
                    Icons.Default.Settings,
                    modifier = entry,
                    inlineValue = true,
                    onClick = { m.navigate("settings") },
                )
                FeatureEntry(
                    "关于",
                    Icons.Default.Info,
                    modifier = entry,
                    inlineValue = true,
                    onClick = { m.navigate("about") },
                )
            }
        }
    }
}

@Composable
internal fun FeatureSettings(s: AppState, m: BiliPlayViewModel, chooseDirectory: () -> Unit) {
    val p = s.appearance
    LazyColumn {
        item { SectionLabel("外观") }
        item {
            FeatureEntry(
                "主题色",
                Icons.Default.Palette,
                if (p.systemColor) "跟随系统"
                else
                    io.github.sodarx.biliplay.ui.theme.ThemePresets.first { it.id == p.themeKey }
                        .name,
                onClick = { m.navigate("colors") },
            )
        }
        items(listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")) { (key, label) ->
            ListItem(
                onClick = { m.appearance(p.copy(appearance = key)) },
                content = { Text(label) },
                leadingContent = {
                    RadioButton(p.appearance == key, { m.appearance(p.copy(appearance = key)) })
                },
            )
        }
        item { SectionLabel("播放与记录") }
        item {
            ListItem(
                content = { Text("智能识别歌曲名称") },
                supportingContent = { Text("显示书名号或引号内的名称") },
                trailingContent = {
                    Switch(p.smartSongTitles, { m.appearance(p.copy(smartSongTitles = it)) })
                },
            )
        }
        item {
            ListItem(
                content = { Text("记录播放历史") },
                trailingContent = {
                    Switch(p.recordHistory, { m.appearance(p.copy(recordHistory = it)) })
                },
            )
        }
        item {
            ListItem(
                content = { Text("记录搜索历史") },
                trailingContent = {
                    Switch(p.recordSearch, { m.appearance(p.copy(recordSearch = it)) })
                },
            )
        }
        item {
            FeatureEntry(
                "点击歌曲时",
                Icons.Default.TouchApp,
                when (p.clickPolicy) {
                    "append" -> "追加并播放"
                    "replace" -> "替换并播放"
                    else -> "每次询问"
                },
                onClick = { m.dialog("clickPolicy") },
            )
        }
        item { SectionLabel("存储") }
        item {
            FeatureEntry(
                "下载保存位置",
                Icons.Default.FolderOpen,
                if (s.downloadTree.isBlank()) "应用音乐目录" else "已选择文件夹",
                onClick = chooseDirectory,
            )
        }
        if (s.downloadTree.isNotBlank())
            item { FeatureEntry("恢复默认保存位置", Icons.Default.Restore, onClick = m::resetDownloadTree) }
        item {
            FeatureEntry(
                "清除图片缓存",
                Icons.Default.CleaningServices,
                onClick = { m.clearImageCache() },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(16.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun AboutScreen(m: BiliPlayViewModel) {
    val c = LocalContext.current
    LazyColumn {
        item {
            Image(
                androidx.compose.ui.res.painterResource(
                    io.github.sodarx.biliplay.R.drawable.windows_logo
                ),
                "Bili Play",
                Modifier.padding(20.dp).size(88.dp),
            )
        }
        item {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bili Play", style = MaterialTheme.typography.headlineLarge)
                Text(BuildConfig.VERSION_NAME)
            }
        }
        item {
            FeatureEntry(
                "GitHub",
                Icons.Default.Code,
                "soda-rx",
                onClick = {
                    c.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/soda-rx/Bili_Play_Android"),
                        )
                    )
                },
            )
        }
        item { SectionLabel("协议") }
        item {
            FeatureEntry("用户协议与隐私政策", Icons.Default.PrivacyTip, onClick = { m.legal("agreement") })
        }
        item {
            FeatureEntry(
                "开源许可",
                Icons.Default.Code,
                "MIT · 第三方许可",
                onClick = { m.legal("license") },
            )
        }
    }
}

@Composable
internal fun LegalScreen(s: AppState) {
    SelectionContainer {
        Text(
            s.legalText,
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun Colors(state: AppState, model: BiliPlayViewModel) {
    val prefs = state.appearance
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ListItem(
                content = { Text("跟随系统取色") },
                trailingContent = {
                    Switch(
                        checked = prefs.systemColor,
                        onCheckedChange = { model.appearance(prefs.copy(systemColor = it)) },
                        enabled = Build.VERSION.SDK_INT >= 31,
                    )
                },
            )
        }
        items(ThemePresets, key = { it.id }) { preset ->
            val selected = !prefs.systemColor && prefs.themeKey == preset.id
            Surface(
                onClick = {
                    model.appearance(prefs.copy(themeKey = preset.id, systemColor = false))
                },
                selected = selected,
                shape = MaterialTheme.shapes.large,
                color =
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val colors =
                        if (
                            state.appearance.appearance == "dark" ||
                                (state.appearance.appearance == "system" && isSystemInDarkTheme())
                        )
                            preset.dark
                        else preset.light
                    Box(
                        Modifier.size(56.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        colors.primaryContainer,
                                        colors.secondaryContainer,
                                        colors.primaryContainer,
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            selected,
                            enter =
                                scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(),
                            exit =
                                scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
                        ) {
                            Icon(Icons.Default.Check, null, tint = colors.onPrimaryContainer)
                        }
                    }
                    Text(preset.name, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
internal fun ThemeSetupScreen(s: AppState, m: BiliPlayViewModel) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("选择外观") }) },
        bottomBar = {
            Surface {
                Button(
                    onClick = m::finishThemeSetup,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) {
                    Text("继续登录")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label)
                    ->
                    FilterChip(
                        selected = s.appearance.appearance == value,
                        onClick = { m.appearance(s.appearance.copy(appearance = value)) },
                        label = { Text(label) },
                    )
                }
            }
            Box(Modifier.weight(1f)) { Colors(s, m) }
        }
    }
}
