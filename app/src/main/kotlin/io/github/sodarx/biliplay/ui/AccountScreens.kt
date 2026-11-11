@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sodarx.biliplay.core.*
import kotlinx.coroutines.isActive

@Composable
internal fun ProfilesScreen(s: AppState, m: BiliPlayViewModel) {
    var removing by remember { mutableStateOf<String?>(null) }
    LazyColumn {
        items(s.profiles.filter { it.id != "guest" && it.account != null }, key = { it.id }) { p ->
            ListItem(
                onClick = { m.switchProfile(p.id) },
                content = { Text(p.account?.name ?: "未登录") },
                supportingContent = p.account?.let { { Text("UID ${it.id}") } },
                leadingContent = {
                    RadioButton(selected = s.profileId == p.id, onClick = { m.switchProfile(p.id) })
                },
                trailingContent = {
                    if (p.canDelete)
                        IconButton(onClick = { removing = p.id }) {
                            Icon(Icons.Default.DeleteOutline, "删除账号")
                        }
                },
            )
        }
        item { FeatureEntry("添加账号", Icons.Default.PersonAdd, onClick = m::startLogin) }
        if (s.account != null)
            item {
                FeatureEntry(
                    "退出当前账号",
                    Icons.AutoMirrored.Filled.Logout,
                    onClick = { m.dialog("logout") },
                )
            }
    }
    removing?.let { id ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("删除账号？") },
            text = { Text("将移除登录凭据，保留本地记录和下载文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        m.removeProfile(id)
                        removing = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
}

@Composable
internal fun LoginScreen(s: AppState, m: BiliPlayViewModel) {
    key(s.loginEpoch) { WebLogin(m, Modifier.fillMaxSize()) }
}

@Composable
private fun WebLogin(m: BiliPlayViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val background = MaterialTheme.colorScheme.background.toArgb()
    var web by remember { mutableStateOf<WebView?>(null) }
    val auth = remember { AuthWebSession(context) }
    var cookiesPrepared by remember { mutableStateOf(false) }
    var page by remember {
        mutableStateOf(
            "https://passport.bilibili.com/h5-app/passport/login?gourl=https%3A%2F%2Fwww.bilibili.com%2F"
        )
    }
    var browserReady by remember { mutableStateOf(false) }
    DisposableEffect(auth) { onDispose { auth.close() } }
    LaunchedEffect(auth) {
        browserReady = auth.acquire()
        if (!browserReady) return@LaunchedEffect
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            kotlinx.coroutines.delay(700)
            val header = auth.cookies()
            if (header.contains("SESSDATA=")) m.completeWebLogin(header)
        }
    }
    if (!browserReady) {
        Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularWavyProgressIndicator(Modifier.size(28.dp))
        }
        return
    }
    Box(modifier.clipToBounds()) {
        // Rebuild only the themed view, retaining its isolated login profile and cookies.
        key(dark) {
            AndroidView(
                factory = {
                    val themed =
                        android.view.ContextThemeWrapper(
                            context,
                            if (dark) io.github.sodarx.biliplay.R.style.Theme_BiliPlay_WebDark
                            else io.github.sodarx.biliplay.R.style.Theme_BiliPlay_WebLight,
                        )
                    WebView(themed).also { view ->
                        web = view
                        view.layoutParams =
                            android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        view.settings.useWideViewPort = true
                        view.settings.loadWithOverviewMode = true
                        view.settings.textZoom = 100
                        view.isVerticalScrollBarEnabled = true
                        view.setBackgroundColor(background)
                        val cookies = auth.attach(view)
                        view.settings.javaScriptEnabled = true
                        view.settings.domStorageEnabled = true
                        view.settings.allowFileAccess = false
                        view.settings.allowContentAccess = false
                        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        if (
                            androidx.webkit.WebViewFeature.isFeatureSupported(
                                androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING
                            )
                        )
                            androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                                view.settings,
                                dark,
                            )
                        view.webViewClient =
                            object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ) =
                                    if (request.isForMainFrame) !safeBili(request.url.toString())
                                    else request.url.scheme != "https"

                                override fun onPageFinished(view: WebView, url: String) {
                                    if (web === view && safeBili(url)) {
                                        page = url
                                        val header = auth.cookies()
                                        if (header.contains("SESSDATA=")) m.completeWebLogin(header)
                                    }
                                }
                            }
                        cookies.setAcceptThirdPartyCookies(view, false)
                        if (cookiesPrepared) view.loadUrl(page)
                        else
                            cookies.removeAllCookies {
                                cookiesPrepared = true
                                if (web === view) {
                                    view.clearCache(true)
                                    view.loadUrl(page)
                                }
                            }
                    }
                },
                update = { it.setBackgroundColor(background) },
                onRelease = { view ->
                    if (web === view) web = null
                    view.stopLoading()
                    view.clearHistory()
                    view.clearCache(true)
                    view.destroy()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
