package io.github.sodarx.biliplay.update

import android.content.Context
import android.content.pm.PackageManager
import io.github.sodarx.biliplay.BuildConfig
import io.github.sodarx.biliplay.core.ReleaseUpdate
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

data class AppUpdateState(
    val release: ReleaseUpdate? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val ready: Boolean = false,
    val showDialog: Boolean = false,
    val autoDownload: Boolean = false,
    val reminders: Boolean = true,
    val message: String? = null,
)

/** Public GitHub traffic uses its own client and never receives account cookies or tokens. */
class AppUpdateManager(private val context: Context) {
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()

    init {
        // v2 defaults apply once to every install: background auto download is opt-in and update
        // reminders are on. Explicit choices made after this migration are kept.
        if (!preferences.getBoolean("defaults_v2", false)) {
            preferences.edit()
                .putBoolean("auto_download", false)
                .putBoolean("reminders", true)
                .putBoolean("defaults_v2", true)
                .apply()
        }
    }

    private val initialAutoDownload = preferences.getBoolean("auto_download", false)
    private val mutable = MutableStateFlow(AppUpdateState(
        autoDownload = initialAutoDownload,
        reminders = initialAutoDownload || preferences.getBoolean("reminders", true)))
    val state = mutable.asStateFlow()
    private var checking: Job? = null
    private var download: Job? = null
    private var autoDownloadPending = false
    private var checkedAt = 0L
    private val directory get() = File(context.cacheDir, "updates")
    private fun apk(release: ReleaseUpdate) = File(directory, "Bili_Play_Android_${release.version}.apk")

    fun automaticDownload(value: Boolean) {
        preferences.edit().putBoolean("auto_download", value).apply()
        if (value) preferences.edit().putBoolean("reminders", true).apply()
        mutable.update { it.copy(autoDownload = value, reminders = if (value) true else it.reminders) }
        if (!value) {
            autoDownloadPending = false
            download?.cancel()
        } else if (state.value.release != null) {
            // Downloads wait until the app is actively in use; launching alone must not fetch.
            autoDownloadPending = true
        }
    }

    /** Starts a queued automatic download only while the app is in use, never at cold start. */
    fun startDeferredDownload() {
        if (!autoDownloadPending || !state.value.autoDownload) return
        if (state.value.release == null || state.value.ready || state.value.downloading) return
        if (download?.isActive == true) return
        autoDownloadPending = false
        download()
    }

    fun reminders(value: Boolean) {
        if (!value && state.value.autoDownload) return
        preferences.edit().putBoolean("reminders", value).apply()
        mutable.update { it.copy(reminders = value) }
    }

    fun dismiss() { mutable.update { it.copy(showDialog = false) } }

    fun installationFailed() {
        mutable.update { it.copy(message = "无法打开安装页面，请在正式 Release 页面下载并手动安装") }
    }

    fun check(manual: Boolean = false) {
        if (checking?.isActive == true) return
        if (!manual && System.currentTimeMillis() - checkedAt < 30 * 60_000) {
            if (state.value.release != null && state.value.reminders)
                mutable.update { it.copy(showDialog = true) }
            return
        }
        checking = scope.launch {
            mutable.update { it.copy(checking = true, message = null) }
            try {
                val release = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url(ReleaseUpdate.LatestUrl)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "BiliPlay-Android/${BuildConfig.VERSION_NAME}").build()).execute().use { response ->
                        if (response.code == 404) null
                        else {
                            check(response.isSuccessful) { "暂时无法检查更新，请稍后重试" }
                            val body = response.body ?: error("更新信息为空")
                            check(body.contentLength() <= 1024 * 1024) { "更新信息过大" }
                            val bytes = body.byteStream().use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    check(output.size() + count <= 1024 * 1024) { "更新信息过大" }
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                            check(bytes.size <= 1024 * 1024) { "更新信息过大" }
                            ReleaseUpdate.parse(JSONObject(bytes.toString(Charsets.UTF_8)), BuildConfig.VERSION_NAME)
                        }
                    }
                }
                checkedAt = System.currentTimeMillis()
                val same = state.value.release?.version == release?.version
                if (!same) download?.cancelAndJoin()
                mutable.update { it.copy(release = release, ready = same && it.ready,
                    progress = if (same) it.progress else 0,
                    showDialog = release != null && (manual || it.reminders),
                    message = if (manual && release == null) "当前已是最新版本" else null) }
                autoDownloadPending = release != null && state.value.autoDownload
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (manual) mutable.update { it.copy(message = "检查更新失败，请稍后重试") }
            } finally { mutable.update { it.copy(checking = false) } }
        }
    }

    private fun openDownload(url: String): Response {
        var next = url
        repeat(6) {
            check(ReleaseUpdate.trustedDownloadUrl(next)) { "更新下载地址不受信任" }
            val response = client.newCall(Request.Builder().url(next).build()).execute()
            if (response.code !in 300..399) return response
            val location = response.header("Location")
            val redirected = location?.let { response.request.url.resolve(it)?.toString() }
            response.close()
            next = redirected ?: error("更新下载重定向无效")
        }
        error("更新下载重定向过多")
    }

    fun download(installWhenReady: ((File) -> Unit)? = null) {
        autoDownloadPending = false
        val release = state.value.release ?: return
        if (download?.isActive == true) {
            if (installWhenReady != null) scope.launch {
                download?.join()
                if (state.value.ready) installationFile()?.let(installWhenReady)
            }
            return
        }
        download = scope.launch {
            mutable.update { it.copy(downloading = true, message = null) }
            try {
                val file = withContext(Dispatchers.IO) {
                    directory.mkdirs()
                    val final = apk(release)
                    if (final.isFile && validate(final, release)) return@withContext final
                    val partial = File(directory, "${release.version}.part")
                    try {
                        openDownload(release.downloadUrl).use { response ->
                            check(response.isSuccessful) { "更新下载失败" }
                            val body = response.body ?: error("更新文件为空")
                            check(body.contentLength() == -1L || body.contentLength() == release.size) { "更新文件长度不符" }
                            var count = 0L
                            body.byteStream().use { input -> partial.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    count += n
                                    check(count <= release.size) { "更新文件长度不符" }
                                    output.write(buffer, 0, n)
                                    val progress = (count * 100 / release.size).toInt()
                                    if (progress != state.value.progress) mutable.update { it.copy(progress = progress) }
                                }
                            } }
                            check(count == release.size) { "更新文件下载不完整" }
                        }
                        check(validate(partial, release)) { "更新包身份、签名或版本不匹配" }
                        if (final.exists()) check(final.delete())
                        check(partial.renameTo(final)) { "更新文件保存失败" }
                        directory.listFiles()?.filter { it != final && it.extension in setOf("apk", "part") }?.forEach { it.delete() }
                        final
                    } finally { partial.delete() }
                }
                mutable.update { it.copy(ready = true, progress = 100) }
                if (installWhenReady != null) installWhenReady(file)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.update { it.copy(ready = false, message = "更新包无法下载或与当前安装签名、身份不兼容，请到正式 Release 页面核对") }
            } finally { mutable.update { it.copy(downloading = false) } }
        }
    }

    private fun validate(file: File, release: ReleaseUpdate): Boolean = runCatching {
        if (file.length() != release.size) return false
        if (release.sha256 != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != release.sha256) return false
        }
        @Suppress("DEPRECATION")
        val incoming = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (incoming.packageName != context.packageName || incoming.versionName != release.version ||
            incoming.longVersionCode <= installed.longVersionCode) return false
        val current = installed.signingInfo ?: return false
        val candidate = incoming.signingInfo ?: return false
        val signers = current.apkContentsSigners.map { it.toCharsString() }.toSet()
        val accepted = (if (candidate.hasMultipleSigners()) candidate.apkContentsSigners
            else candidate.signingCertificateHistory).map { it.toCharsString() }.toSet()
        if (current.hasMultipleSigners() || candidate.hasMultipleSigners()) signers == accepted
        else signers.size == 1 && accepted.containsAll(signers)
    }.getOrDefault(false)

    suspend fun installationFile(): File? = withContext(Dispatchers.IO) {
        val release = state.value.release ?: return@withContext null
        apk(release).takeIf { it.isFile && validate(it, release) }
    }
}
