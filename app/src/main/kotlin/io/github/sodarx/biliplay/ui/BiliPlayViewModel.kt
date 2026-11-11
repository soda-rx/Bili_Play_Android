package io.github.sodarx.biliplay.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import coil3.SingletonImageLoader
import io.github.sodarx.biliplay.BiliPlayApplication
import io.github.sodarx.biliplay.core.*
import io.github.sodarx.biliplay.data.*
import io.github.sodarx.biliplay.download.Downloads
import io.github.sodarx.biliplay.playback.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

class BiliPlayViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BiliPlayApplication
    private val repo = app.repository
    private val hub = app.playback
    private val connection = PlaybackConnection(application)
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    val playbackProgress = hub.state
    private var profileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val routeHistory = RouteHistory()
    private var operations = 0
    private var searchJob: Job? = null
    private var suggestionJob: Job? = null
    private var lyricJob: Job? = null
    private var loginJob: Job? = null
    private var candidate: Profile? = null
    private var validatingLogin = false
    private var likedPool: List<Track> = emptyList()
    private var homePage = 1
    private var continueWindowStarted = false
    private var continueOfferActive = true
    private var dailyRequest: Job? = null
    private var likedRequest: Job? = null
    private var homeEpoch = 0
    private var homeRequest: Job? = null
    private var playRequest: Job? = null
    private var favoriteRequest: Job? = null
    private var favoriteStateEpoch = 0L
    private var conversationRequest: Job? = null
    private var followingEpoch = 0

    private data class PendingPlay(
        val track: Track,
        val list: List<Track>,
        val expand: Boolean,
        val origin: PlaybackOrigin?,
    )

    private var pendingPlay: PendingPlay? = null
    private val notificationCursors = mutableMapOf<String, Map<String, String>>()
    private var lyricKey = ""
    private val previewJobs = mutableSetOf<Long>()
    private val previewLimit = kotlinx.coroutines.sync.Semaphore(3)

    init {
        viewModelScope.launch {
            repo.profile.collect { p ->
                profileScope.cancel()
                profileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                operations = 0
                routeHistory.clear()
                notificationCursors.clear()
                lyricKey = ""
                previewJobs.clear()
                likedPool = emptyList()
                val prefs = p.preferences.read()
                mutable.value =
                    AppState(
                        initialized = true,
                        playerDemoPending = !repo.playerDemoDone(),
                        showContinueListening = continueOfferActive,
                        folderOrder =
                            runCatching {
                                    val data = JSONArray(p.library.get("folder_order", "[]"))
                                    (0 until data.length()).map { data.getLong(it) }
                                }
                                .getOrDefault(emptyList()),
                        themeSetupComplete = repo.themeSetupComplete(),
                        route =
                            if (
                                StartupPolicy.step(
                                    repo.agreementAccepted(),
                                    repo.themeSetupComplete(),
                                    repo.loggedIn(p),
                                ) == StartupStep.Login
                            )
                                "login"
                            else "home",
                        appearance = prefs,
                        profileId = p.id,
                        account = p.account.takeIf { repo.loggedIn(p) },
                        profiles = repo.profiles(),
                        agreementAccepted = repo.agreementAccepted(),
                        history = p.library.history(),
                        likedMusic = p.library.tracks("home:desktop-v1:liked"),
                        dailyMusic = p.library.tracks("home:desktop-v1:daily"),
                        recommendations = p.library.tracks("home:desktop-v1:more"),
                        risingMusic = p.library.tracks("home:desktop-v1:chart"),
                        searchHistory = p.library.searchHistory(),
                        downloads = p.library.downloads(),
                        downloadTree = p.library.get("download_tree"),
                    )
                profileScope.launch {
                    p.library.changes.collectLatest {
                        val overview = withContext(Dispatchers.IO) { p.library.overview() }
                        if (current() !== p) return@collectLatest
                        mutable.update {
                            it.copy(
                                history = overview.history,
                                searchHistory = overview.searchHistory,
                                downloads = overview.downloads,
                                downloadTree = overview.downloadTree,
                            )
                        }
                    }
                }
                if (repo.agreementAccepted() && repo.themeSetupComplete()) {
                    if (!repo.loggedIn(p)) startLogin() else refreshHome()
                    if (repo.loggedIn(p)) {
                        refreshAccount()
                        refreshFolders()
                    }
                }
            }
        }
        viewModelScope.launch {
            hub.state.collect { s ->
                if (s.profileId != repo.profile.value.id) return@collect
                mutable.update {
                    it.copy(
                        current = s.current,
                        queue = s.queue,
                        queueIndex = s.queueIndex,
                        playbackOrigin = s.origin,
                        playing = s.playing,
                        buffering = s.buffering,
                        position =
                            if (
                                PlaybackUiPolicy.capturePosition(
                                    it.current?.key,
                                    s.current?.key,
                                    it.playing,
                                    s.playing,
                                )
                            )
                                s.position
                            else it.position,
                        duration = s.duration,
                        sources = s.sources,
                        qualityIndex = s.qualityIndex,
                        speed = s.speed,
                        mode = s.mode,
                        loading = operations > 0 || s.loading,
                    )
                }
                s.error?.let {
                    message(it)
                    hub.clearError()
                }
                val key = s.current?.key.orEmpty()
                if (key.isEmpty()) {
                    lyricKey = ""
                    lyricJob?.cancel()
                    mutable.update {
                        it.copy(
                            lyrics = emptyList(),
                            subtitleTracks = emptyList(),
                            subtitleIndex = -1,
                            playerLyrics = false,
                            favorite = false,
                            currentFollowing = false,
                        )
                    }
                }
                if (key.isNotEmpty() && key != lyricKey) {
                    lyricKey = key
                    loadLyrics()
                    refreshFavorite()
                    refreshCurrentFollowing()
                }
            }
        }
    }

    private var systemPlayerRequest: Job? = null

    fun openPlayerFromSystem() {
        systemPlayerRequest?.cancel()
        systemPlayerRequest = viewModelScope.launch {
            val ready =
                withTimeoutOrNull(10000) {
                    combine(state, hub.state) { ui, playback -> ui to playback }
                        .first { (ui, playback) ->
                            ui.initialized &&
                                ui.profileId == playback.profileId &&
                                ui.current?.key == playback.current?.key
                        }
                } ?: return@launch
            val (ui, playback) = ready
            if (
                PlaybackLaunch.canOpen(
                    ui.agreementAccepted,
                    ui.themeSetupComplete,
                    ui.account != null,
                    playback.current?.key,
                    playback.queue.map { it.key },
                )
            ) {
                if (ui.route == "queue") back()
                if (state.value.route != "player") navigate("player")
            }
        }
    }

    private fun current() = repo.profile.value

    private fun launch(block: suspend (Profile) -> Unit): Job {
        val p = current()
        return profileScope.launch { guarded(p) { block(p) } }
    }

    private suspend fun guarded(p: Profile, block: suspend () -> Unit) {
        operations++
        mutable.update { it.copy(loading = true, pageError = null) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (current() !== p) return
            val text =
                if (e is ApiFailure) e.message ?: "请求未完成"
                else if (e is IllegalArgumentException) e.message ?: "操作无效" else "操作失败，请稍后重试"
            if (e is ApiFailure && e.requiresLogin) {
                repo.markExpired(p.id)
                mutable.update { it.copy(account = null) }
            }
            message(text)
            mutable.update { it.copy(pageError = text) }
        } finally {
            if (current() === p) {
                operations = (operations - 1).coerceAtLeast(0)
                mutable.update { it.copy(loading = operations > 0 || hub.state.value.loading) }
            }
        }
    }

    fun message(text: String) {
        mutable.update { it.copy(message = text) }
    }

    fun clearMessage() {
        mutable.update { it.copy(message = null) }
    }

    fun selectTab(route: String) {
        require(route in CoverPresentation.primaryRoutes)
        if (HomeTabAction.isReselection(state.value.route, route)) {
            mutable.update { it.copy(homeActionId = it.homeActionId + 1) }
            return
        }
        navigate(route)
        mutable.update {
            it.copy(tabNavigationId = it.tabNavigationId + 1)
        }
    }

    fun navigate(route: String) {
        if (
            state.value.agreementAccepted &&
                state.value.themeSetupComplete &&
                !repo.loggedIn() &&
                route !in listOf("login", "legal", "profiles")
        ) {
            startLogin()
            return
        }
        val destination =
            routeHistory.navigate(RouteVisit(state.value.route, state.value.routeVisit), route)
        mutable.update {
            it.copy(
                route = route,
                routeVisit = destination.id,
                playerLyrics =
                    CoverPresentation.lyricsAfterNavigation(it.route, route, it.playerLyrics),
                playerSession =
                    if (CoverPresentation.isNewPlayerEntry(it.route, route)) it.playerSession + 1
                    else it.playerSession,
                islandMinimized = if (route == "search") false else it.islandMinimized,
                motionBack =
                    CoverPresentation.primaryRoutes.let { tabs ->
                        it.route in tabs &&
                            route in tabs &&
                            tabs.indexOf(route) < tabs.indexOf(it.route)
                    },
                tab = if (route in CoverPresentation.primaryRoutes) route else it.tab,
                pageError = null,
            )
        }
        when (route) {
            "favorites" -> if (state.value.account != null) refreshFolders()
            "notifications" -> loadNotifications()
            "profiles" -> mutable.update { it.copy(profiles = repo.profiles()) }
        }
    }

    fun back() {
        if (state.value.account == null && state.value.route == "login") return
        if (state.value.route == "login") cancelLogin()
        val destination = routeHistory.back(state.value.tab)
        val route = destination.route
        mutable.update {
            it.copy(
                route = route,
                routeVisit = destination.id,
                playerLyrics =
                    CoverPresentation.lyricsAfterNavigation(it.route, route, it.playerLyrics),
                playerSession =
                    if (CoverPresentation.isNewPlayerEntry(it.route, route)) it.playerSession + 1
                    else it.playerSession,
                motionBack = true,
                pageError = null,
            )
        }
    }

    fun dialog(value: String?, track: Track? = null) {
        mutable.update { it.copy(dialog = value, actionTrack = track ?: it.actionTrack) }
        if (value in listOf("trackActions", "favorite"))
            (track ?: state.value.actionTrack)?.let(::loadActionFavorites)
    }

    fun dismissDialog() {
        mutable.update { it.copy(dialog = null) }
    }

    fun acceptAgreement() {
        repo.acceptAgreement()
        mutable.update { it.copy(agreementAccepted = true) }
        if (repo.themeSetupComplete()) {
            if (repo.loggedIn()) {
                refreshHome()
                refreshAccount()
                refreshFolders()
            } else startLogin()
        }
    }

    fun finishThemeSetup() {
        repo.finishThemeSetup()
        mutable.update { it.copy(themeSetupComplete = true) }
        if (repo.loggedIn()) {
            refreshHome()
            refreshAccount()
            refreshFolders()
        } else startLogin()
    }

    fun appearance(value: AppearanceSettings) {
        current().preferences.write(value)
        mutable.update { it.copy(appearance = value) }
    }

    fun refreshAccount() = launch { p ->
        val account = p.api.account()
        require(p.account?.id == account.id) { "账号已变更，请重新登录" }
        repo.updateAccount(account)
        val counts = p.api.socialCounts(account.id)
        mutable.update {
            it.copy(
                account = account,
                followingCount = counts.first,
                followersCount = counts.second,
            )
        }
    }

    private fun requireLogin(): Boolean {
        if (state.value.account != null && repo.loggedIn()) return true
        startLogin()
        return false
    }

    fun startLogin() {
        cancelLogin()
        candidate = repo.newLogin()
        mutable.update {
            it.copy(
                loginStatus = "",
                loginBusy = false,
                loginEpoch = it.loginEpoch + 1,
                dialog = null,
            )
        }
        navigate("login")
    }

    fun completeWebLogin(header: String) {
        val p = candidate ?: return
        if (validatingLogin) return
        if (!header.contains("SESSDATA=")) {
            message("请先完成哔哩哔哩登录")
            return
        }
        validatingLogin = true
        loginJob = viewModelScope.launch {
            mutable.update { it.copy(loginBusy = true) }
            try {
                p.api.sessions.importHeader(header)
                finishLogin(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("未能验证登录状态，请重试")
            } finally {
                validatingLogin = false
                mutable.update { it.copy(loginBusy = false) }
            }
        }
    }

    private suspend fun finishLogin(p: Profile) {
        val account = p.api.account()
        if (candidate !== p) return
        hub.stop()
        candidate = null
        validatingLogin = false
        repo.acceptLogin(p, account)
        message("已登录")
    }

    fun cancelLogin() {
        loginJob?.cancel()
        candidate?.let { repo.cancelLogin(it) }
        candidate = null
        validatingLogin = false
    }

    fun switchProfile(id: String) {
        cancelLogin()
        hub.stop()
        repo.switch(id)
    }

    fun logout() {
        dismissDialog()
        hub.stop()
        WorkManager.getInstance(app).cancelAllWorkByTag("profile:${current().id}")
        repo.logout()
    }

    fun removeProfile(id: String) {
        if (!repo.canRemoveProfile(id)) {
            message("请先退出该账号后再删除")
            return
        }
        if (current().id == id) hub.stop()
        WorkManager.getInstance(app).cancelAllWorkByTag("profile:$id")
        repo.removeProfile(id)
        mutable.update { it.copy(profiles = repo.profiles()) }
    }

    fun query(value: String) {
        suggestionJob?.cancel()
        val p = current()
        val local =
            state.value.searchHistory
                .filter { it.contains(value, ignoreCase = true) && value.isNotBlank() }
                .take(6)
        mutable.update {
            it.copy(query = value, suggestions = local, suggestionTracks = emptyList())
        }
        if (value.isBlank()) return
        suggestionJob = profileScope.launch {
            delay(200)
            launch {
                val words =
                    try {
                        p.api.suggestions(value)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                if (state.value.query == value)
                    mutable.update { it.copy(suggestions = (words + local).distinct().take(8)) }
            }
            launch {
                val songs =
                    try {
                        p.api.search(value).take(5)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                if (state.value.query == value) mutable.update { it.copy(suggestionTracks = songs) }
            }
        }
    }

    fun search(kind: String = state.value.searchKind, more: Boolean = false) {
        val q = state.value.query.trim()
        if (q.isBlank()) return
        searchJob?.cancel()
        if (!more) navigate("search")
        val p = current()
        searchJob = profileScope.launch {
            guarded(p) {
                val page = if (more) state.value.searchPage + 1 else 1
                if (p.preferences.read().recordSearch) p.library.rememberSearch(q)
                if (kind == "uploaders") {
                    val result = p.api.searchUploaders(q, page)
                    mutable.update {
                        it.copy(
                            searchKind = kind,
                            uploaders =
                                if (more) (it.uploaders + result.items).distinctBy { u -> u.id }
                                else result.items,
                            searchPage = page,
                            searchHasMore = result.hasMore,
                        )
                    }
                } else {
                    val input = p.api.resolveVideoInput(q)
                    val result =
                        if (input != null) Page(p.api.video(input).parts, false, 1)
                        else p.api.searchPage(q, page)
                    mutable.update {
                        it.copy(
                            searchKind = kind,
                            results =
                                if (more)
                                    (it.results + result.items).distinctBy { t -> t.bvid to t.page }
                                else result.items,
                            searchPage = page,
                            searchHasMore = result.hasMore,
                        )
                    }
                    if (page == 1 && input == null) {
                        val extras =
                            withTimeoutOrNull(6500) {
                                    coroutineScope {
                                        PreferredMusic.uploaders
                                            .map { uid ->
                                                async {
                                                    runCatching {
                                                            p.api.uploaderVideos(uid, 1, q).items
                                                        }
                                                        .getOrDefault(emptyList())
                                                }
                                            }
                                            .awaitAll()
                                            .flatten()
                                    }
                                }
                                .orEmpty()
                        if (extras.isNotEmpty())
                            mutable.update {
                                it.copy(results = PreferredMusic.rank(extras + it.results, q))
                            }
                    }
                }
            }
        }
    }

    fun deleteSearch(value: String) {
        current().library.deleteSearch(value)
    }

    fun sharedText(value: String) {
        val match = Regex("https?://[^\\s]+|BV[0-9A-Za-z]{10}").find(value)?.value ?: value
        query(match)
        navigate("home")
    }

    fun homeVisible() {
        if (continueWindowStarted) return
        continueWindowStarted = true
        viewModelScope.launch {
            delay(5000)
            continueOfferActive = false
            mutable.update { it.copy(showContinueListening = false) }
        }
    }

    private fun hideContinueListening() {
        continueOfferActive = false
        mutable.update { it.copy(showContinueListening = false) }
    }

    fun refreshHomeFromGesture() {
        hideContinueListening()
        refreshHome()
    }

    private suspend fun <T> musicRequest(block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiFailure) {
            if (e.requiresLogin) throw e else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    fun refreshHome() {
        likedRequest?.cancel()
        dailyRequest?.cancel()
        homeRequest?.cancel()
        homePage = 1
        val epoch = ++homeEpoch
        val p = current()
        homeRequest = profileScope.launch {
            mutable.update { it.copy(homeRefreshing = true) }
            try {
                guarded(p) {
                    coroutineScope {
                        val feed = async { musicRequest { p.api.personalMusic() } }
                        val rank = async {
                            try {
                                p.api.recentMusicLeaders()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (e is ApiFailure && e.requiresLogin) throw e
                                if (epoch == homeEpoch)
                                    mutable.update { it.copy(chartError = "音乐热榜暂不可用") }
                                emptyList()
                            }
                        }
                        val f = feed.await()
                        val r = f.filter { it.track.uploaderId in PreferredMusic.uploaders }
                        val hot = rank.await()
                        val sections = MusicRecommendations.sections(r, f)
                        likedPool = (r + f).map { it.track }.distinctBy { it.key }
                        if (epoch == homeEpoch)
                            mutable.update {
                                it.copy(
                                    likedMusic = sections.likes.ifEmpty { it.likedMusic },
                                    dailyMusic = sections.daily.ifEmpty { it.dailyMusic },
                                    recommendations = sections.more.ifEmpty { it.recommendations },
                                    risingMusic = hot.ifEmpty { it.risingMusic },
                                    chartError = if (hot.isNotEmpty()) null else it.chartError,
                                    homeHasMore = f.isNotEmpty(),
                                    history = p.library.history(),
                                    pageError =
                                        if (r.isEmpty() && f.isEmpty()) "B站个性化音乐推荐暂不可用" else null,
                                )
                            }
                        if (epoch == homeEpoch)
                            withContext(Dispatchers.IO) {
                                if (sections.likes.isNotEmpty())
                                    p.library.saveTracks("home:desktop-v1:liked", sections.likes)
                                if (sections.daily.isNotEmpty())
                                    p.library.saveTracks("home:desktop-v1:daily", sections.daily)
                                if (sections.more.isNotEmpty())
                                    p.library.saveTracks("home:desktop-v1:more", sections.more)
                                if (hot.isNotEmpty())
                                    p.library.saveTracks("home:desktop-v1:chart", hot)
                            }
                    }
                }
            } finally {
                if (current() === p && epoch == homeEpoch)
                    mutable.update { it.copy(homeRefreshing = false) }
            }
        }
    }

    fun refreshDaily() {
        if (state.value.dailyRefreshing || state.value.homeRefreshing) return
        val p = current()
        val epoch = homeEpoch
        val previous = state.value.dailyMusic.map { it.key }.toSet()
        dailyRequest = profileScope.launch {
            mutable.update { it.copy(dailyRefreshing = true) }
            try {
                val pool = p.api.desktopMusic(independent = true).items.distinctBy { it.key }
                if (pool.isEmpty()) throw ApiFailure(-1, "今日推荐暂不可用")
                val daily = pool.sortedBy { it.key in previous }.take(8)
                if (current() === p && epoch == homeEpoch) {
                    mutable.update { it.copy(dailyMusic = daily) }
                    withContext(Dispatchers.IO) {
                        p.library.saveTracks("home:desktop-v1:daily", daily)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ApiFailure && e.requiresLogin) repo.markExpired(p.id)
                else message("暂时无法刷新今日推荐")
            } finally {
                if (current() === p) mutable.update { it.copy(dailyRefreshing = false) }
            }
        }
    }

    fun refreshLiked() {
        hideContinueListening()
        if (state.value.homeRefreshing || state.value.likedRefreshing) return
        val p = current()
        val previous = state.value.likedMusic
        likedRequest = profileScope.launch {
            mutable.update { it.copy(likedRefreshing = true) }
            try {
                val related = musicRequest {
                    p.api.desktopMusic(independent = true).items.map { MusicCandidate(it, 0) }
                }
                    .filter { it.track.uploaderId in PreferredMusic.uploaders }
                val candidates =
                    related
                        .map { it.track }
                        .ifEmpty {
                            likedPool.ifEmpty {
                                state.value.likedMusic +
                                    state.value.dailyMusic +
                                    state.value.recommendations
                            }
                        }
                if (current() === p)
                    mutable.update {
                        it.copy(likedMusic = LikedSelection.select(candidates, previous))
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message("暂时无法刷新猜你喜欢")
            } finally {
                if (current() === p) mutable.update { it.copy(likedRefreshing = false) }
            }
        }
    }

    fun openCoin() {
        if (!requireLogin()) return
        val track = state.value.current ?: return
        dialog("coins", track)
        mutable.update { it.copy(coinCount = 0, coinAmount = 1) }
        launch { p ->
            val count = p.api.coinCount(track)
            mutable.update { it.copy(coinCount = count) }
        }
    }

    fun coinAmount(value: Int) {
        if (value in 1..2) mutable.update { it.copy(coinAmount = value) }
    }

    fun confirmCoin() {
        val s = state.value
        val track = s.actionTrack ?: return
        if (s.coinPosting || s.coinAmount + s.coinCount > 2) return
        launch { p ->
            mutable.update { it.copy(coinPosting = true) }
            try {
                p.api.giveCoins(track, s.coinAmount)
                dismissDialog()
                message("已投币")
            } finally {
                if (current() === p) mutable.update { it.copy(coinPosting = false) }
            }
        }
    }

    fun play(track: Track, queue: List<Track>? = null) =
        requestPlay(track, queue ?: listOf(track), true)

    private fun contextOrigin(): PlaybackOrigin? {
        val s = state.value
        return when (s.route) {
            "folder" ->
                s.selectedFolder?.let { PlaybackOrigin("folder", "${it.id}", it.title, it.cover) }
            else -> null
        }
    }

    private fun requestPlay(
        track: Track,
        list: List<Track>,
        expandCollection: Boolean,
        origin: PlaybackOrigin? = contextOrigin(),
    ) {
        hideContinueListening()
        if (state.value.appearance.clickPolicy == "ask" && state.value.queue.isNotEmpty()) {
            pendingPlay = PendingPlay(track, list, expandCollection, origin)
            dialog("queueChange")
            return
        }
        playWithPolicy(
            track,
            list,
            state.value.appearance.clickPolicy == "append",
            expandCollection,
            origin,
        )
    }

    private fun playWithPolicy(
        track: Track,
        list: List<Track>,
        append: Boolean,
        expandCollection: Boolean,
        origin: PlaybackOrigin?,
    ) {
        playRequest?.cancel()
        playRequest = launch { p ->
            connection.ready()
            val network =
                app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
            val connected =
                network
                    .getNetworkCapabilities(network.activeNetwork)
                    ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val downloaded =
                p.library.downloads().any { it.track.key == track.key && it.status == "complete" }
            val cached = p.library.tracks("related:${track.bvid}")
            val video =
                if (expandCollection && (!downloaded || connected))
                    try {
                        withTimeoutOrNull(6000) {
                            withContext(Dispatchers.IO) {
                                p.api.video(VideoInput(track.bvid, track.page))
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is ApiFailure && e.requiresLogin) throw e
                        null
                    }
                else null
            val related =
                if (expandCollection)
                    CollectionQueue.tracks(
                        track,
                        video,
                        if (video == null && cached.isNotEmpty()) cached else list,
                    )
                else list
            if (video != null && (video.collection.isNotEmpty() || video.parts.size > 1))
                withContext(Dispatchers.IO) {
                    p.library.saveTracks("related:${track.bvid}", related)
                }
            val q = if (append) (hub.state.value.queue + related).distinctBy { it.key } else related
            val selected = related.firstOrNull { it.key == track.key } ?: track
            val source = if (expandCollection) PlaybackOrigin.fromVideo(video) ?: origin else origin
            val queueSource =
                if (
                    append && hub.state.value.queue.isNotEmpty() && hub.state.value.origin != source
                )
                    null
                else source
            hub.play(selected, q, origin = queueSource)
        }
    }

    fun confirmQueue(append: Boolean) {
        pendingPlay?.let { playWithPolicy(it.track, it.list, append, it.expand, it.origin) }
        pendingPlay = null
        dismissDialog()
    }

    fun playFromQueue(track: Track, index: Int) {
        playRequest?.cancel()
        playRequest = launch { p ->
            connection.ready()
            if (
                current() === p &&
                    hub.state.value.profileId == p.id &&
                    hub.state.value.queue.getOrNull(index)?.key == track.key
            )
                hub.play(track, queueIndex = index)
        }
    }

    fun playAll(tracks: List<Track>, origin: PlaybackOrigin? = contextOrigin()) {
        tracks.firstOrNull()?.let { requestPlay(it, tracks, false, origin) }
    }

    fun toggle() {
        launch {
            connection.ready()
            hub.toggle()
        }
    }

    fun seek(position: Long) = hub.seek(position)

    fun setSpeed(value: Float) = hub.speed(value)

    fun changeQuality(index: Int) = hub.quality(index)

    fun next(direction: Int = 1) {
        launch {
            connection.ready()
            hub.next(direction)
        }
    }

    fun setMode(mode: QueueMode) {
        hub.mode(mode)
        dismissDialog()
    }

    fun cycleMode() {
        val modes = QueueMode.entries
        hub.mode(modes[(modes.indexOf(state.value.mode) + 1) % modes.size])
    }

    fun enqueue(track: Track, next: Boolean = false) {
        hub.enqueue(track, next)
        dismissDialog()
        message(if (next) "已设为下一首播放" else "已加入播放队列")
    }

    fun removeQueue(index: Int) {
        playRequest?.cancel()
        pendingPlay = null
        hub.remove(index)
    }

    fun moveQueue(index: Int, direction: Int) = hub.move(index, direction)

    fun dragQueue(from: Int, to: Int) {
        if (hub.state.value.profileId == state.value.profileId)
            hub.move(from, to - from, persistNow = false)
    }

    fun saveQueueOrder() {
        if (hub.state.value.profileId == state.value.profileId) hub.saveQueueOrder()
    }

    fun clearQueue() {
        playRequest?.cancel()
        pendingPlay = null
        hideContinueListening()
        hub.clearQueue()
        dismissDialog()
    }

    fun clearHistory() {
        current().library.clearHistory()
        dismissDialog()
    }

    fun refreshFolders() = launch { p ->
        val account = state.value.account ?: return@launch
        val folders = p.api.folders(account.id)
        mutable.update { it.copy(folders = folders) }
    }

    fun previewFolder(folder: Folder) {
        if (
            folder.count <= 0 ||
                state.value.folderCovers.containsKey(folder.id) ||
                !previewJobs.add(folder.id)
        )
            return
        val p = current()
        profileScope.launch {
            try {
                previewLimit.acquire()
                try {
                    val covers =
                        p.api
                            .folderTracks(folder.id)
                            .items
                            .map { it.cover }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .take(3)
                    if (current() === p)
                        mutable.update {
                            it.copy(folderCovers = it.folderCovers + (folder.id to covers))
                        }
                } finally {
                    previewLimit.release()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                /* Preview failure does not block opening the folder. */
            } finally {
                if (current() === p) previewJobs.remove(folder.id)
            }
        }
    }

    fun sortedFolders(): List<Folder> {
        val order = state.value.folderOrder
        return state.value.folders.sortedBy {
            order.indexOf(it.id).let { index -> if (index < 0) Int.MAX_VALUE else index }
        }
    }

    fun reorderFolder(from: Long, target: Long) {
        val ids = sortedFolders().map { it.id }
        mutable.update { it.copy(folderOrder = FolderOrder.move(ids, from, target)) }
    }

    fun saveFolderOrder() {
        current().library.put("folder_order", JSONArray(sortedFolders().map { it.id }).toString())
    }

    fun moveFolder(folder: Folder, direction: Int) {
        val all = sortedFolders()
        val index = all.indexOfFirst { it.id == folder.id }
        val target = all.getOrNull(index + direction) ?: return
        reorderFolder(folder.id, target.id)
        saveFolderOrder()
    }

    fun folderActions(folder: Folder) {
        mutable.update { it.copy(selectedFolder = folder, dialog = "folderActions") }
    }

    fun playFolder(folder: Folder) = launch { p ->
        val all = mutableListOf<Track>()
        var page = 1
        do {
            val result = p.api.folderTracks(folder.id, page++)
            all += result.items
            if (!result.hasMore || result.items.isEmpty()) break
        } while (page <= 500)
        playAll(
            all.distinctBy { it.key },
            PlaybackOrigin("folder", "${folder.id}", folder.title, folder.cover),
        )
    }

    fun openFolder(folder: Folder) {
        mutable.update {
            it.copy(
                selectedFolder = folder,
                folderTracks = emptyList(),
                folderPage = 1,
                folderHasMore = false,
            )
        }
        navigate("folder")
        loadFolder()
    }

    fun loadFolder(more: Boolean = false) = launch { p ->
        val folder = state.value.selectedFolder ?: return@launch
        val page = if (more) state.value.folderPage + 1 else 1
        val result = p.api.folderTracks(folder.id, page)
        mutable.update {
            it.copy(
                folderTracks =
                    if (more) (it.folderTracks + result.items).distinctBy { t -> t.bvid to t.page }
                    else result.items,
                folderPage = page,
                folderHasMore = result.hasMore,
            )
        }
    }

    fun newFolder() {
        if (!requireLogin()) return
        mutable.update { it.copy(folderDraft = "", folderPrivate = true, dialog = "newFolder") }
    }

    fun editFolder() {
        val f = state.value.selectedFolder ?: return
        if (!f.owned) return
        mutable.update {
            it.copy(folderDraft = f.title, folderPrivate = f.isPrivate, dialog = "editFolder")
        }
    }

    fun folderDraft(value: String) {
        mutable.update { it.copy(folderDraft = value.take(80)) }
    }

    fun folderPrivate(value: Boolean) {
        mutable.update { it.copy(folderPrivate = value) }
    }

    fun saveFolder() = launch { p ->
        val s = state.value
        if (s.dialog == "editFolder") {
            val f = s.selectedFolder ?: return@launch
            p.api.editFolder(f, s.folderDraft, s.folderPrivate)
            mutable.update {
                it.copy(
                    selectedFolder =
                        f.copy(title = s.folderDraft.trim(), isPrivate = s.folderPrivate)
                )
            }
        } else p.api.createFolder(s.folderDraft, s.folderPrivate)
        dismissDialog()
        refreshFolders()
    }

    fun deleteFolder() = launch { p ->
        val f = state.value.selectedFolder ?: return@launch
        p.api.deleteFolder(f)
        dismissDialog()
        back()
        refreshFolders()
    }

    private fun loadActionFavorites(track: Track) {
        favoriteRequest?.cancel()
        mutable.update { it.copy(actionFavorites = null) }
        if (state.value.account == null) return
        favoriteRequest = launch { p ->
            val uid = p.account?.id ?: return@launch
            val memberships = p.api.favoriteMembership(track, uid)
            if (
                state.value.actionTrack?.key == track.key &&
                    state.value.dialog in listOf("trackActions", "favorite")
            )
                mutable.update { it.copy(actionFavorites = memberships) }
        }
    }

    fun chooseFavorite(track: Track) {
        if (!requireLogin()) return
        dialog("favorite", track)
        refreshFolders()
    }

    fun setFavorite(folder: Folder, add: Boolean) {
        if (!requireLogin() || state.value.favoriteSaving || !folder.owned) return
        val t = state.value.actionTrack ?: state.value.current ?: return
        ++favoriteStateEpoch
        val sourceDialog = state.value.dialog
        val picker = sourceDialog == "favorite"
        mutable.update { it.copy(favoriteSaving = true) }
        launch { p ->
            try {
                p.api.favorite(t, folder, add)
                ++favoriteStateEpoch
                mutable.update {
                    val memberships =
                        if (it.actionTrack?.key == t.key) it.actionFavorites?.plus(folder.id to add)
                        else it.actionFavorites
                    it.copy(
                        folderCovers = it.folderCovers - folder.id,
                        actionFavorites = memberships,
                        favorite =
                            if (
                                it.current?.key == t.key &&
                                    memberships != null &&
                                    it.actionTrack?.key == t.key
                            )
                                memberships.values.any { v -> v }
                            else it.favorite,
                    )
                }
                refreshFolders()
                if (
                    !picker &&
                        state.value.dialog == sourceDialog &&
                        state.value.actionTrack?.key == t.key
                )
                    dismissDialog()
                refreshFavorite(reset = false)
                if (state.value.selectedFolder?.id == folder.id) loadFolder()
                message(if (add) "已收藏" else "已取消收藏")
            } finally {
                if (current() === p) mutable.update { it.copy(favoriteSaving = false) }
            }
        }
    }

    private fun refreshFavorite(reset: Boolean = true) {
        val t = state.value.current ?: return
        if (reset) mutable.update { it.copy(favorite = false) }
        val epoch = favoriteStateEpoch
        if (state.value.account != null)
            launch { p ->
                val value = p.api.favorited(t)
                if (favoriteStateEpoch == epoch && state.value.current?.key == t.key)
                    mutable.update { it.copy(favorite = value) }
            }
    }

    fun openUploader(user: Uploader) {
        mutable.update {
            it.copy(
                selectedUploader = user,
                uploaderTracks = emptyList(),
                uploaderPage = 1,
                uploaderHasMore = false,
                isFollowing = false,
            )
        }
        navigate("uploader")
        loadUploader()
        val epoch = followingEpoch
        if (state.value.account != null)
            launch { p ->
                val following = p.api.following(user.id)
                if (followingEpoch == epoch && state.value.selectedUploader?.id == user.id)
                    mutable.update { it.copy(isFollowing = following) }
            }
    }

    fun minimizeIsland(value: Boolean) {
        mutable.update { it.copy(islandMinimized = value) }
    }

    fun resumeListening() {
        if (state.value.current == null) return
        hideContinueListening()
        if (!state.value.playing) toggle()
        navigate("player")
    }

    /** Await the exact previewed item, so slow/failing networks never commit a different page. */
    suspend fun switchPlayerTrack(track: Track, index: Int): Boolean {
        val p = current()
        connection.ready()
        if (
            current() !== p ||
                hub.state.value.profileId != p.id ||
                hub.state.value.queue.getOrNull(index)?.key != track.key
        )
            return false
        hub.play(track, startPosition = 0, queueIndex = index).join()
        return hub.state.value.current?.key == track.key && hub.state.value.queueIndex == index
    }

    fun setPlayerLyrics(value: Boolean, session: Long, trackKey: String) {
        val current = state.value
        if (
            !CoverPresentation.acceptsMediaEvent(
                current.route,
                current.playerSession,
                current.current?.key,
                session,
                trackKey,
            )
        )
            return
        mutable.update { it.copy(playerLyrics = value, playerDemoPending = false) }
        repo.markPlayerDemoDone()
    }

    fun finishPlayerDemo() {
        repo.markPlayerDemoDone()
        mutable.update { it.copy(playerDemoPending = false) }
    }

    fun seekRelative(delta: Long) = seek(hub.state.value.position + delta)

    private fun refreshCurrentFollowing() {

        val track = state.value.current ?: return

        mutable.update { it.copy(currentFollowing = false) }

        if (track.uploaderId <= 0 || state.value.account == null) return

        val epoch = followingEpoch
        profileScope.launch {
            val p = current()

            val following =
                try {

                    p.api.following(track.uploaderId)
                } catch (e: CancellationException) {

                    throw e
                } catch (_: Exception) {

                    false
                }

            if (
                current() === p &&
                    followingEpoch == epoch &&
                    state.value.current?.uploaderId == track.uploaderId
            )
                mutable.update { it.copy(currentFollowing = following) }
        }
    }

    fun followCurrent() {
        if (!requireLogin() || state.value.followingCurrentBusy) return
        val uid = state.value.current?.uploaderId?.takeIf { it > 0 } ?: return
        setFollowing(uid, !state.value.currentFollowing)
    }

    private fun setFollowing(uid: Long, desired: Boolean) {
        ++followingEpoch
        mutable.update { it.copy(followingCurrentBusy = true) }
        launch { p ->
            try {
                p.api.follow(uid, desired)
                mutable.update {
                    it.copy(
                        currentFollowing =
                            if (it.current?.uploaderId == uid) desired else it.currentFollowing,
                        isFollowing =
                            if (it.selectedUploader?.id == uid) desired else it.isFollowing,
                    )
                }
                refreshAccount()
                message(if (desired) "已关注" else "已取消关注")
            } finally {
                if (current() === p) {
                    ++followingEpoch
                    mutable.update { it.copy(followingCurrentBusy = false) }
                    if (state.value.current?.uploaderId != uid) refreshCurrentFollowing()
                    val selected = state.value.selectedUploader
                    if (selected != null && selected.id != uid) {
                        val epoch = followingEpoch
                        launch { profile ->
                            val following = profile.api.following(selected.id)
                            if (
                                followingEpoch == epoch &&
                                    state.value.selectedUploader?.id == selected.id
                            )
                                mutable.update { it.copy(isFollowing = following) }
                        }
                    }
                }
            }
        }
    }

    fun openCurrentUploader() {
        val t = state.value.current ?: return
        openUploader(Uploader(t.uploaderId, t.uploader))
    }

    fun loadUploader(more: Boolean = false) = launch { p ->
        val user = state.value.selectedUploader ?: return@launch
        val page = if (more) state.value.uploaderPage + 1 else 1
        if (!more) {
            val profile =
                try {
                    p.api.uploaderProfile(user.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    user
                }
            if (state.value.selectedUploader?.id == user.id)
                mutable.update { it.copy(selectedUploader = profile) }
        }
        val result = p.api.uploaderVideos(user.id, page, musicOnly = false)
        if (state.value.selectedUploader?.id != user.id) return@launch
        mutable.update {
            it.copy(
                uploaderTracks =
                    if (more) (it.uploaderTracks + result.items).distinctBy { t -> t.bvid }
                    else result.items,
                uploaderPage = page,
                uploaderHasMore = result.hasMore,
            )
        }
    }

    fun follow() {
        if (!requireLogin() || state.value.followingCurrentBusy) return
        val uid = state.value.selectedUploader?.id?.takeIf { it > 0 } ?: return
        setFollowing(uid, !state.value.isFollowing)
    }

    fun social(followers: Boolean) {
        if (!requireLogin()) return
        mutable.update {
            it.copy(
                socialFollowers = followers,
                socialUsers = emptyList(),
                socialPage = 1,
                socialHasMore = false,
            )
        }
        navigate("social")
        loadSocial()
    }

    fun loadSocial(more: Boolean = false) = launch { p ->
        val account = state.value.account ?: return@launch
        val page = if (more) state.value.socialPage + 1 else 1
        val result = p.api.socialUsers(account.id, state.value.socialFollowers, page)
        mutable.update {
            it.copy(
                socialUsers =
                    if (more) (it.socialUsers + result.items).distinctBy { u -> u.id }
                    else result.items,
                socialPage = page,
                socialHasMore = result.hasMore,
            )
        }
    }

    fun loadLyrics() {
        lyricJob?.cancel()
        val p = current()
        val track = state.value.current ?: return
        mutable.update {
            it.copy(
                lyrics = emptyList(),
                subtitleTracks = emptyList(),
                subtitleIndex = -1,
                followLyrics = true,
            )
        }
        lyricJob = profileScope.launch {
            val cached = p.library.get("lyrics:${track.key}")
            if (cached.isNotBlank())
                runCatching { parseSubtitle(JSONObject().put("body", JSONArray(cached))) }
                    .onSuccess { lines -> mutable.update { it.copy(lyrics = lines) } }
            if (track.localUri.isNotEmpty() && cached.isNotEmpty()) return@launch
            try {
                val tracks = p.api.subtitleTracks(track)
                val choice = p.library.get("subtitle_choice:${track.key}")
                val selected =
                    tracks.firstOrNull { it.language == choice }
                        ?: SubtitlePolicy.choose(tracks, track.title)
                val lines = selected?.let { p.api.subtitle(it) }.orEmpty()
                if (state.value.current?.key != track.key) return@launch
                mutable.update {
                    it.copy(
                        subtitleTracks = tracks,
                        subtitleIndex = tracks.indexOf(selected),
                        lyrics = lines,
                    )
                }
                cacheLyrics(p, track, lines)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                /* A missing subtitle must not interrupt music. */
            }
        }
    }

    private fun cacheLyrics(p: Profile, t: Track, lines: List<SubtitleLine>) {
        if (lines.isNotEmpty())
            p.library.put(
                "lyrics:${t.key}",
                JSONArray()
                    .apply {
                        lines.forEach {
                            put(
                                JSONObject()
                                    .put("from", it.from)
                                    .put("to", it.to)
                                    .put("content", it.content)
                            )
                        }
                    }
                    .toString(),
            )
    }

    fun subtitle(index: Int) = launch { p ->
        val track = state.value.current ?: return@launch
        val selected = state.value.subtitleTracks.getOrNull(index) ?: return@launch
        val lines = p.api.subtitle(selected)
        if (state.value.current?.key == track.key) {
            mutable.update { it.copy(subtitleIndex = index, lyrics = lines) }
            p.library.put("subtitle_choice:${track.key}", selected.language)
            cacheLyrics(p, track, lines)
        }
    }

    fun followLyrics(value: Boolean) {
        mutable.update { it.copy(followLyrics = value) }
    }

    fun dismissComments() {
        if (state.value.route == "replies") back()
        if (state.value.route == "comments") back()
    }

    fun openComments() {
        val t = state.value.current ?: return
        mutable.update {
            it.copy(
                commentTrack = t,
                comments = emptyList(),
                commentRoot = null,
                replyTarget = null,
                commentDraft = "",
                commentPage = 1,
            )
        }
        navigate("comments")
        loadComments()
    }

    fun loadComments(more: Boolean = false) = launch { p ->
        val track = state.value.commentTrack ?: return@launch
        val page = if (more) state.value.commentPage + 1 else 1
        val result = p.api.comments(track.aid, state.value.commentLatest, page)
        mutable.update {
            it.copy(
                comments =
                    if (more) (it.comments + result.items).distinctBy { c -> c.id }
                    else result.items,
                commentPage = page,
                commentHasMore = result.hasMore,
            )
        }
    }

    fun commentSort(latest: Boolean) {
        mutable.update { it.copy(commentLatest = latest) }
        loadComments()
    }

    fun openReplies(comment: Comment) {
        mutable.update { it.copy(commentRoot = comment, replies = emptyList(), replyPage = 1) }
        navigate("replies")
        loadReplies()
    }

    fun loadReplies(more: Boolean = false) = launch { p ->
        val track = state.value.commentTrack ?: return@launch
        val root = state.value.commentRoot ?: return@launch
        val page = if (more) state.value.replyPage + 1 else 1
        val result = p.api.replies(track.aid, root.id, page)
        if (state.value.commentTrack?.aid != track.aid || state.value.commentRoot?.id != root.id)
            return@launch
        mutable.update {
            it.copy(
                replies =
                    if (more) (it.replies + result.items).distinctBy { c -> c.id }
                    else result.items,
                replyPage = page,
                replyHasMore = result.hasMore,
            )
        }
    }

    fun reply(comment: Comment?) {
        mutable.update { it.copy(replyTarget = comment) }
    }

    fun commentDraft(value: String) {
        mutable.update { it.copy(commentDraft = value.take(1000)) }
    }

    fun sendComment() {
        if (!requireLogin() || state.value.posting) return
        launch { p ->
            mutable.update { it.copy(posting = true) }
            try {
                val s = state.value
                val track = s.commentTrack ?: return@launch
                val account = p.api.account()
                require(account.id == p.account?.id) { "账号已变更，请重新登录" }
                val target = s.replyTarget
                val root = if (target == null) 0L else s.commentRoot?.id ?: target.id
                p.api.sendComment(track.aid, s.commentDraft, root, target?.id ?: 0)
                mutable.update { it.copy(commentDraft = "", replyTarget = null) }
                if (s.route == "replies") loadReplies() else loadComments()
                message("已发送")
            } finally {
                mutable.update { it.copy(posting = false) }
            }
        }
    }

    fun react(comment: Comment, dislike: Boolean) {
        if (!requireLogin()) return
        launch { p ->
            val track = state.value.commentTrack ?: return@launch
            val value = if (dislike) 2 else 1
            val enabled = comment.reaction != value
            p.api.react(track.aid, comment.id, dislike, enabled)
            val updated =
                comment.copy(
                    reaction = if (enabled) value else 0,
                    likes =
                        (comment.likes +
                                (if (value == 1 && enabled) 1
                                else if (comment.reaction == 1) -1 else 0))
                            .coerceAtLeast(0),
                )
            mutable.update {
                it.copy(
                    comments = it.comments.map { c -> if (c.id == updated.id) updated else c },
                    replies = it.replies.map { c -> if (c.id == updated.id) updated else c },
                )
            }
        }
    }

    fun emotes() {
        if (!requireLogin() || state.value.emotes.isNotEmpty()) return
        launch { p ->
            val all = p.api.emotes()
            mutable.update { it.copy(emotes = all) }
        }
    }

    fun insertEmote(text: String) {
        commentDraft(state.value.commentDraft + text)
    }

    fun notificationCategory(category: String) {
        mutable.update {
            it.copy(
                notificationCategory = category,
                notifications = emptyList(),
                notificationHasMore = false,
            )
        }
        notificationCursors.clear()
        loadNotifications()
    }

    fun loadNotifications(more: Boolean = false) {
        if (!requireLogin()) return
        launch { p ->
            val sources =
                when (state.value.notificationCategory) {
                    "私信" -> listOf("session")
                    "点赞" -> listOf("like")
                    "回复与 @" -> listOf("reply", "at")
                    else -> listOf("system", "user-system")
                }
            if (!more) notificationCursors.clear()
            var errors = 0
            val result = mutableListOf<Notification>()
            for (source in sources) {
                if (more && notificationCursors[source].isNullOrEmpty()) continue
                try {
                    val page =
                        p.api.notifications(
                            source,
                            if (more) notificationCursors[source].orEmpty() else emptyMap(),
                        )
                    result += page.items
                    notificationCursors[source] = if (page.hasMore) page.cursor else emptyMap()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    errors++
                }
            }
            mutable.update {
                it.copy(
                    notifications =
                        ((if (more) it.notifications else emptyList()) + result)
                            .distinctBy { n -> n.id }
                            .sortedByDescending { n -> n.timestamp },
                    notificationHasMore = notificationCursors.values.any { c -> c.isNotEmpty() },
                    notificationWarning = if (errors > 0) "部分通知读取失败，请刷新重试" else null,
                )
            }
        }
    }

    fun openNotification(notification: Notification) {
        if (notification.category == "私信") {
            openConversation(notification)
            return
        }
        val url = NotificationLinks.resolve(notification.url)
        if (url != null) {
            val opened = runCatching {
                app.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
                .isSuccess
            if (opened) return
        }
        mutable.update { it.copy(selectedNotification = notification) }
        navigate("notificationDetail")
    }

    fun openConversation(notification: Notification) {
        if (!requireLogin()) return
        if (
            notification.talkerId <= 0 ||
                notification.sessionType != 1 ||
                notification.talkerId == state.value.account?.id
        ) {
            message("暂不支持此会话类型")
            return
        }
        conversationRequest?.cancel()
        mutable.update {
            it.copy(
                conversation = notification,
                conversationMessages = emptyList(),
                conversationBefore = 0,
                conversationHasMore = false,
            )
        }
        navigate("conversation")
        loadConversation()
    }

    fun loadConversation(more: Boolean = false) {
        if (conversationRequest?.isActive == true) return
        val notification = state.value.conversation ?: return
        val before = if (more) state.value.conversationBefore else 0
        if (more && (!state.value.conversationHasMore || before <= 0)) return
        conversationRequest = launch { p ->
            val self = p.account?.id ?: return@launch
            val page = p.api.conversation(self, notification.talkerId, before)
            if (
                state.value.route != "conversation" ||
                    state.value.conversation?.talkerId != notification.talkerId
            )
                return@launch
            mutable.update {
                it.copy(
                    conversationMessages =
                        ((if (more) it.conversationMessages else emptyList()) + page.items)
                            .distinctBy { row -> row.id }
                            .sortedBy { row -> row.sequence },
                    conversationBefore = page.before,
                    conversationHasMore = page.hasMore,
                )
            }
        }
    }

    fun openDownloads() {
        navigate("downloads")
        reconcileDownloads()
    }

    private fun reconcileDownloads() = launch { p ->
        for (row in
            p.library.downloads().filter {
                it.status in listOf("queued", "downloading", "encoding")
            }) {
            val info =
                withContext(Dispatchers.IO) {
                    WorkManager.getInstance(app).getWorkInfoById(UUID.fromString(row.id)).get()
                }
            if (info == null || info.state.isFinished)
                p.library.download(
                    row.copy(
                        status =
                            if (info?.state == androidx.work.WorkInfo.State.CANCELLED) "cancelled"
                            else "failed",
                        error = "任务已结束，请重试",
                    )
                )
        }
    }

    fun download(track: Track) {
        dialog("download", track)
        mutable.update {
            it.copy(downloadSources = emptyList(), downloadQuality = "标准", downloadBitrate = 192000)
        }
        launch { p ->
            val t =
                if (track.cid > 0) track
                else
                    p.api.video(VideoInput(track.bvid, track.page)).parts.first {
                        it.page == track.page
                    }
            val all = p.api.audio(t, p.account?.vip == true)
            mutable.update { it.copy(actionTrack = t, downloadSources = all) }
        }
    }

    fun downloadQuality(value: String) {
        mutable.update { it.copy(downloadQuality = value) }
    }

    fun downloadBitrate(value: Int) {
        mutable.update { it.copy(downloadBitrate = value) }
    }

    fun confirmDownload() {
        val s = state.value
        val track = s.actionTrack ?: return
        if (s.downloadSources.isEmpty()) return
        val p = current()
        if (
            p.library.downloads().any {
                it.track.bvid == track.bvid &&
                    it.track.page == track.page &&
                    it.status in listOf("queued", "downloading", "encoding")
            }
        ) {
            message("该歌曲已在下载任务中")
            dismissDialog()
            return
        }
        Downloads.enqueue(app, p, track, s.downloadQuality, s.downloadBitrate)
        dismissDialog()
        message("已加入下载任务")
    }

    fun cancelDownload(entry: DownloadEntry) {
        WorkManager.getInstance(app).cancelWorkById(UUID.fromString(entry.id))
        current().library.download(entry.copy(status = "cancelled"))
    }

    fun retryDownload(entry: DownloadEntry) {
        download(entry.track)
    }

    fun selectDownload(id: String) {
        mutable.update {
            it.copy(
                selectedDownloads =
                    if (id in it.selectedDownloads) it.selectedDownloads - id
                    else it.selectedDownloads + id
            )
        }
    }

    fun selectAllDownloads() {
        mutable.update {
            it.copy(
                selectedDownloads =
                    it.downloads.filter { r -> r.status == "complete" }.map { r -> r.id }.toSet()
            )
        }
    }

    fun downloadTasks(value: Boolean) {
        mutable.update { it.copy(downloadTasks = value, selectedDownloads = emptySet()) }
    }

    fun deleteDownloads() = launch { p ->
        val rows = state.value.downloads.filter { it.id in state.value.selectedDownloads }
        for (row in rows) {
            WorkManager.getInstance(app).cancelWorkById(UUID.fromString(row.id))
            withContext(Dispatchers.IO) {
                val info =
                    WorkManager.getInstance(app).getWorkInfoById(UUID.fromString(row.id)).get()
                if (info != null && !info.state.isFinished)
                    withTimeout(15000) {
                        WorkManager.getInstance(app)
                            .getWorkInfoByIdFlow(UUID.fromString(row.id))
                            .filterNotNull()
                            .first { it.state.isFinished }
                    }
            }
            val uri = row.uri.takeIf { it.isNotEmpty() }?.let(Uri::parse)
            if (uri?.scheme == "content") app.contentResolver.delete(uri, null, null)
            else if (uri?.scheme == "file") {
                val f = File(uri.path!!).canonicalFile
                val base =
                    (app.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
                            ?: app.filesDir)
                        .canonicalFile
                require(f.path.startsWith(File(base, "downloads/${p.id}").path + File.separator))
                check(!f.exists() || f.delete())
            }
            p.library.forgetDownload(row.id)
        }
        mutable.update { it.copy(selectedDownloads = emptySet()) }
        dismissDialog()
    }

    fun downloadTree(uri: Uri) {
        app.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        current().library.put("download_tree", uri.toString())
    }

    fun resetDownloadTree() {
        current().library.put("download_tree", "")
    }

    fun clearImageCache() = launch {
        withContext(Dispatchers.IO) { SingletonImageLoader.get(app).diskCache?.clear() }
        SingletonImageLoader.get(app).memoryCache?.clear()
        message("图片缓存已清理")
    }

    fun legal(name: String) {
        val file =
            when (name) {
                "license" -> "licenses.txt"
                else -> "agreement.txt"
            }
        val text = app.assets.open(file).bufferedReader().use { it.readText() }
        mutable.update {
            it.copy(legalText = text, legalTitle = if (name == "license") "开源许可" else "用户协议与隐私政策")
        }
        navigate("legal")
    }

    fun moreHome() {
        if (state.value.loading || state.value.homeRefreshing || !state.value.homeHasMore) return
        val epoch = homeEpoch
        launch { p ->
            val next = homePage + 1
            val rows = p.api.personalMusic(next).map { it.track }
            if (epoch != homeEpoch) return@launch
            homePage = next
            mutable.update {
                val combined = (it.recommendations + rows).distinctBy { t -> t.key }
                it.copy(
                    recommendations = combined,
                    homeHasMore = rows.isNotEmpty() && combined.size > it.recommendations.size,
                )
            }
        }
    }

    fun more() {
        if (state.value.loading) return
        when (state.value.route) {
            "home" -> moreHome()
            "search" -> search(more = true)
            "folder" -> loadFolder(true)
            "uploader" -> loadUploader(true)
            "social" -> loadSocial(true)
            "comments" -> loadComments(true)
            "replies" -> loadReplies(true)
            "notifications" -> loadNotifications(true)
            "conversation" -> loadConversation(true)
        }
    }

    override fun onCleared() {
        cancelLogin()
        profileScope.cancel()
        connection.close()
        super.onCleared()
    }
}
