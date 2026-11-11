@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sodarx.biliplay.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sodarx.biliplay.core.*
import io.github.sodarx.biliplay.ui.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun BiliPlayRoot(sharedText: String?, model: BiliPlayViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(sharedText) { if (!sharedText.isNullOrBlank()) model.sharedText(sharedText) }
    BiliPlayTheme(
        state.appearance.themeKey,
        state.appearance.systemColor,
        state.appearance.appearance,
    ) {
        if (!state.initialized) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator()
            }
            return@BiliPlayTheme
        }
        if (state.agreementAccepted && !state.themeSetupComplete) {
            ThemeSetupScreen(state, model)
            return@BiliPlayTheme
        }
        val commentsOpen = state.route in listOf("comments", "replies")
        var underlyingRoute by remember(state.profileId) { mutableStateOf("home") }
        var underlyingVisit by remember(state.profileId) { mutableLongStateOf(0) }
        SideEffect {
            if (!commentsOpen) {
                underlyingRoute = state.route
                underlyingVisit = state.routeVisit
            }
        }
        val sceneState =
            if (commentsOpen) state.copy(route = underlyingRoute, routeVisit = underlyingVisit)
            else state
        val chromeState =
            if (commentsOpen && sceneState.route != "player") sceneState.copy(route = "comments")
            else sceneState
        PlayerMotionRoot(chromeState, model) {
            val snackbar = remember { SnackbarHostState() }
            val coverMotion = LocalCoverMotion.current
            val headerStatusTop = WindowInsets.statusBars.getTop(LocalDensity.current)
            val circleShowing =
                state.current != null &&
                    state.account != null &&
                    state.islandMinimized &&
                    sceneState.route in CoverPresentation.playerBarRoutes &&
                    !commentsOpen
            val animatedTopReservation =
                animateDpAsState(
                    if (circleShowing) 64.dp else 0.dp,
                    MaterialTheme.motionScheme.defaultSpatialSpec(),
                    label = "toolbar space for rotating player",
                )
            val searchState = rememberSearchBarState()
            val searchField = rememberTextFieldState(state.query)
            val scope = rememberCoroutineScope()
            var folderMenu by remember { mutableStateOf(false) }
            var quality by remember { mutableStateOf(false) }
            var speed by remember { mutableStateOf(false) }
            var directoryProfile by remember { mutableStateOf("") }
            val chooseDirectory =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri
                    ->
                    if (uri != null) {
                        if (directoryProfile == model.state.value.profileId)
                            runCatching { model.downloadTree(uri) }
                                .onFailure { model.message("无法保留该文件夹的访问权限") }
                        else model.message("账号已切换，请重新选择文件夹")
                    }
                }
            val notificationPermission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    model.confirmDownload()
                }
            val confirmDownload = {
                if (
                    Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                )
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else model.confirmDownload()
            }
            LaunchedEffect(state.query) {
                if (searchField.text.toString() != state.query)
                    searchField.edit { replace(0, length, state.query) }
            }
            LaunchedEffect(searchField) {
                snapshotFlow { searchField.text.toString() }
                    .distinctUntilChanged()
                    .collect { if (model.state.value.query != it) model.query(it) }
            }
            val submit = {
                model.query(searchField.text.toString())
                model.search()
                scope.launch { searchState.animateToCollapsed() }
                Unit
            }
            val searchInput: @Composable () -> Unit = {
                SearchBarDefaults.InputField(
                    textFieldState = searchField,
                    searchBarState = searchState,
                    onSearch = { submit() },
                    modifier =
                        Modifier.onGloballyPositioned {
                            if (
                                state.route == "home" &&
                                    searchState.targetValue == SearchBarValue.Collapsed
                            )
                                coverMotion?.reportToolbar("home", it.boundsInRoot().center.y)
                        },
                    placeholder = { Text("搜索歌曲、UP主及更多") },
                    leadingIcon = {
                        if (searchState.targetValue == SearchBarValue.Expanded)
                            IconButton(
                                onClick = { scope.launch { searchState.animateToCollapsed() } }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                            }
                        else Icon(Icons.Default.Search, null)
                    },
                    trailingIcon = {
                        IconButton(onClick = submit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "搜索")
                        }
                    },
                )
            }
            LaunchedEffect(state.message) {
                state.message?.let {
                    snackbar.showSnackbar(it)
                    model.clearMessage()
                }
            }
            BackHandler(state.route !in CoverPresentation.primaryRoutes) {
                if (state.route == "login" && state.account == null)
                    (context as? android.app.Activity)?.finish()
                else {
                    coverMotion?.freeze()
                    model.back()
                }
            }
            LaunchedEffect(state.account, state.route) {
                if (
                    state.agreementAccepted &&
                        state.themeSetupComplete &&
                        state.account == null &&
                        state.route !in listOf("login", "legal", "profiles")
                )
                    model.startLogin()
            }
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .zIndex(
                            CoverPresentation.navigationLayer(
                                sceneState.route,
                                sceneState.account != null,
                                commentsOpen,
                                coverMotion?.playerSheet != null,
                            )
                        )
                ) {
                    PlayerNavigation(
                        if (commentsOpen) sceneState.copy(route = "comments") else sceneState,
                        model,
                    )
                }
                Box(Modifier.fillMaxSize()) {
                    MotionRoute(
                        sceneState,
                        onMainPage = model::navigate,
                        pageOverlay = { page -> PlayerPageCapsule(page, model) },
                    ) { screen ->
                        val navigationSpace =
                            CoverPresentation.bottomNavigationSpace(
                                    screen.route,
                                    screen.account != null,
                                    coverMotion?.navigationHeight?.value,
                                    WindowInsets.navigationBars
                                        .asPaddingValues()
                                        .calculateBottomPadding()
                                        .value,
                                )
                                .dp
                        Scaffold(
                            modifier = Modifier.padding(bottom = navigationSpace),
                            containerColor =
                                if (screen.route in listOf("player", "queue"))
                                    androidx.compose.ui.graphics.Color.Transparent
                                else MaterialTheme.colorScheme.background,
                            contentWindowInsets =
                                if (
                                    screen.account != null &&
                                        screen.route in CoverPresentation.playerBarRoutes &&
                                        !commentsOpen
                                )
                                    WindowInsets.safeDrawing.only(
                                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                                    )
                                else ScaffoldDefaults.contentWindowInsets,
                            snackbarHost = {
                                ExpressiveSnackbarHost(
                                    snackbar,
                                    if (
                                        sceneState.route in CoverPresentation.playerBarRoutes &&
                                            state.current != null &&
                                            !state.islandMinimized &&
                                            !commentsOpen
                                    )
                                        88.dp
                                    else 0.dp,
                                )
                            },
                            topBar = {
                                if (screen.route == "home")
                                    SearchBar(
                                        state = searchState,
                                        inputField = searchInput,
                                        modifier =
                                            Modifier.fillMaxWidth()
                                                .windowInsetsPadding(
                                                    WindowInsets.safeDrawing.only(
                                                        WindowInsetsSides.Top +
                                                            WindowInsetsSides.Horizontal
                                                    )
                                                )
                                                .padding(
                                                    rememberPlayerToolbarPadding(
                                                        animatedTopReservation,
                                                        16.dp,
                                                        8.dp,
                                                    )
                                                ),
                                    )
                                else
                                    TopAppBar(
                                        colors =
                                            if (screen.route in listOf("player", "queue"))
                                                TopAppBarDefaults.topAppBarColors(
                                                    containerColor =
                                                        androidx.compose.ui.graphics.Color
                                                            .Transparent,
                                                    scrolledContainerColor =
                                                        androidx.compose.ui.graphics.Color
                                                            .Transparent,
                                                )
                                            else TopAppBarDefaults.topAppBarColors(),
                                        modifier =
                                            Modifier.padding(
                                                    if (
                                                        screen.route in
                                                            CoverPresentation.playerBarRoutes
                                                    )
                                                        rememberPlayerToolbarPadding(
                                                            animatedTopReservation
                                                        )
                                                    else PaddingValues(0.dp)
                                                )
                                                .onGloballyPositioned {
                                                    val bounds = it.boundsInRoot()
                                                    val statusTop = headerStatusTop
                                                    if (
                                                        screen.route == "search" &&
                                                            screen.route == sceneState.route
                                                    )
                                                        coverMotion?.reportToolbar(
                                                            screen.route,
                                                            (maxOf(
                                                                bounds.top,
                                                                statusTop.toFloat(),
                                                            ) + bounds.bottom) / 2f,
                                                        )
                                                },
                                        title = {
                                            if (screen.route == "search")
                                                Surface(shape = MaterialTheme.shapes.extraLarge) {
                                                    searchInput()
                                                }
                                            else
                                                Text(
                                                    screenTitle(screen),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                        },
                                        navigationIcon = {
                                            if (
                                                screen.route !in listOf("favorites", "mine") &&
                                                    !(screen.route == "login" &&
                                                        screen.account == null)
                                            )
                                                IconButton(
                                                    onClick = {
                                                        coverMotion?.freeze()
                                                        model.back()
                                                    }
                                                ) {
                                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                                                }
                                        },
                                        actions = {
                                            when (screen.route) {
                                                "login" ->
                                                    IconButton(onClick = model::startLogin) {
                                                        Icon(Icons.Default.Refresh, "重新加载登录网页")
                                                    }
                                                "favorites" -> {
                                                    IconButton(
                                                        onClick = model::newFolder,
                                                        modifier =
                                                            Modifier.onGloballyPositioned {
                                                                coverMotion?.reportToolbar(
                                                                    "favorites",
                                                                    it.boundsInRoot().center.y,
                                                                )
                                                            },
                                                    ) {
                                                        Icon(Icons.Default.Add, "新建收藏夹")
                                                    }
                                                }
                                                "folder" ->
                                                    if (screen.selectedFolder?.owned == true) {
                                                        IconButton(
                                                            onClick = { folderMenu = true }
                                                        ) {
                                                            Icon(Icons.Default.MoreVert, "收藏夹管理")
                                                        }
                                                        DropdownMenu(
                                                            folderMenu,
                                                            { folderMenu = false },
                                                        ) {
                                                            DropdownMenuItem(
                                                                text = { Text("编辑收藏夹") },
                                                                onClick = {
                                                                    folderMenu = false
                                                                    model.editFolder()
                                                                },
                                                            )
                                                            DropdownMenuItem(
                                                                text = { Text("删除收藏夹") },
                                                                onClick = {
                                                                    folderMenu = false
                                                                    model.dialog("deleteFolder")
                                                                },
                                                            )
                                                        }
                                                    }
                                                "mine" ->
                                                    IconButton(
                                                        onClick = {
                                                            model.navigate("notifications")
                                                        },
                                                        modifier =
                                                            Modifier.onGloballyPositioned {
                                                                if (sceneState.route == "mine")
                                                                    coverMotion?.reportToolbar(
                                                                        "mine",
                                                                        it.boundsInRoot().center.y,
                                                                    )
                                                            },
                                                    ) {
                                                        Icon(Icons.Default.Notifications, "通知中心")
                                                    }
                                                "notifications" ->
                                                    IconButton(
                                                        onClick = { model.loadNotifications() }
                                                    ) {
                                                        Icon(Icons.Default.Refresh, "刷新通知")
                                                    }
                                                "queue" ->
                                                    IconButton(
                                                        onClick = { model.dialog("clearQueue") },
                                                        enabled = screen.queue.isNotEmpty(),
                                                    ) {
                                                        Icon(Icons.Default.DeleteOutline, "清空队列")
                                                    }
                                                "history" ->
                                                    IconButton(
                                                        onClick = { model.dialog("clearHistory") },
                                                        enabled = screen.history.isNotEmpty(),
                                                    ) {
                                                        Icon(Icons.Default.DeleteOutline, "清除记录")
                                                    }
                                                "downloads" -> {
                                                    IconButton(
                                                        onClick = model::selectAllDownloads
                                                    ) {
                                                        Icon(Icons.Default.SelectAll, "全选已下载歌曲")
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            model.dialog("deleteDownloads")
                                                        },
                                                        enabled =
                                                            screen.selectedDownloads.isNotEmpty(),
                                                    ) {
                                                        Icon(Icons.Default.DeleteOutline, "删除所选下载")
                                                    }
                                                }
                                                "player" -> {
                                                    if (screen.playerLyrics) {
                                                        IconButton(
                                                            onClick = {
                                                                model.dialog("subtitleLanguage")
                                                            },
                                                            enabled =
                                                                screen.subtitleTracks.isNotEmpty(),
                                                        ) {
                                                            Icon(Icons.Default.Translate, "字幕语言")
                                                        }
                                                    }
                                                    IconButton(
                                                        onClick = { model.navigate("queue") }
                                                    ) {
                                                        Icon(
                                                            Icons.AutoMirrored.Filled.QueueMusic,
                                                            "播放列表",
                                                        )
                                                    }
                                                }
                                                "profiles" ->
                                                    IconButton(onClick = model::startLogin) {
                                                        Icon(Icons.Default.PersonAdd, "添加账号")
                                                    }
                                                "search" ->
                                                    IconButton(
                                                        onClick = { model.navigate("home") }
                                                    ) {
                                                        Icon(Icons.Default.Search, "修改搜索")
                                                    }
                                            }
                                        },
                                    )
                            },
                        ) { padding ->
                            Box(
                                Modifier.fillMaxSize()
                                    .padding(padding)
                                    .consumeWindowInsets(padding)
                                    .then(
                                        if (screen.route == "login") Modifier.imePadding()
                                        else Modifier
                                    )
                            ) {
                                if (screen.agreementAccepted)
                                    when (screen.route) {
                                        "home" -> Home(screen, model)
                                        "search" -> SearchResults(screen, model)
                                        "favorites" -> FavoritesScreen(screen, model)
                                        "folder" -> FolderScreen(screen, model)
                                        "mine" -> FeatureMine(screen, model)
                                        "settings" ->
                                            FeatureSettings(screen, model) {
                                                directoryProfile = screen.profileId
                                                chooseDirectory.launch(null)
                                            }
                                        "colors" -> Colors(screen, model)
                                        "player",
                                        "queue" ->
                                            PlaybackContent(
                                                screen,
                                                model,
                                                { quality = true },
                                                { speed = true },
                                            )
                                        "history" -> FeatureHistory(screen, model)
                                        "comments",
                                        "replies" -> CommentsScreen(screen, model)
                                        "notifications" -> NotificationsScreen(screen, model)
                                        "notificationDetail" -> NotificationDetailScreen(screen)
                                        "conversation" -> ConversationScreen(screen, model)
                                        "downloads" -> DownloadsScreen(screen, model)
                                        "profiles" -> ProfilesScreen(screen, model)
                                        "login" -> LoginScreen(screen, model)
                                        "uploader" -> UploaderScreen(screen, model)
                                        "social" -> SocialScreen(screen, model)
                                        "legal" -> LegalScreen(screen)
                                        "about" -> AboutScreen(model)
                                    }

                                androidx.compose.animation.AnimatedVisibility(
                                    (screen.loading || screen.loginBusy || screen.buffering) &&
                                        screen.route !in listOf("player", "home"),
                                    modifier = Modifier.align(Alignment.TopCenter),
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                ) {
                                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
            if (commentsOpen) CommentSheet(state, model, snackbar)
            ExpandedFullScreenSearchBar(state = searchState, inputField = searchInput) {
                SearchSuggestions(
                    state,
                    model,
                    onQuery = { query ->
                        model.query(query)
                        searchField.edit { replace(0, length, query) }
                        submit()
                    },
                    onPlay = { track ->
                        model.play(track)
                        scope.launch { searchState.animateToCollapsed() }
                    },
                )
            }
            if (state.agreementAccepted) FeatureDialogs(state, model, confirmDownload)
            if (quality) QualityDialog(state, model) { quality = false }
            if (speed) SpeedDialog(state, model) { speed = false }
            if (!state.agreementAccepted) {
                val agreement = remember {
                    context.assets.open("agreement.txt").bufferedReader().use { it.readText() }
                }
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("用户协议与隐私政策") },
                    text = {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                agreement,
                                Modifier.heightIn(max = 400.dp)
                                    .verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = model::acceptAgreement) { Text("同意并继续") }
                    },
                    dismissButton = {
                        TextButton(onClick = { (context as? android.app.Activity)?.finish() }) {
                            Text("退出")
                        }
                    },
                )
            }
        }
    }
}

private fun screenTitle(s: AppState) =
    when (s.route) {
        "favorites" -> "收藏"
        "mine" -> "我的"
        "settings" -> "设置"
        "colors" -> "主题色"
        "search" -> s.query.ifBlank { "搜索" }
        "player" -> "正在播放"
        "queue" -> "播放队列"
        "history" -> "历史记录"
        "folder" -> s.selectedFolder?.title ?: "收藏夹"
        "uploader" -> "UP 主"
        "social" -> if (s.socialFollowers) "粉丝" else "关注"
        "notifications" -> "通知中心"
        "notificationDetail" -> s.selectedNotification?.title?.ifBlank { "通知内容" } ?: "通知内容"
        "conversation" -> s.conversation?.title?.ifBlank { "私信" } ?: "私信"
        "downloads" -> "下载"
        "profiles" -> "账号管理"
        "login" -> "登录"
        "comments" -> "评论"
        "replies" -> "回复"
        "legal" -> s.legalTitle
        else -> "关于"
    }
