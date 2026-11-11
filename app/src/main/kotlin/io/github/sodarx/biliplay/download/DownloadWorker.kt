package io.github.sodarx.biliplay.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import io.github.sodarx.biliplay.BiliPlayApplication
import io.github.sodarx.biliplay.R
import io.github.sodarx.biliplay.core.*
import io.github.sodarx.biliplay.data.*
import java.io.File
import kotlinx.coroutines.*

class DownloadWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val app = applicationContext as BiliPlayApplication
            val profile =
                app.repository.open(
                    inputData.getString("profile") ?: return@withContext Result.failure()
                )
            val track =
                runCatching {
                    TrackJson.decode(org.json.JSONObject(inputData.getString("track") ?: ""))
                }
                    .getOrNull() ?: return@withContext Result.failure()
            val bitrate = inputData.getInt("bitrate", 192000)
            if (bitrate !in listOf(192000, 320000)) return@withContext Result.failure()
            val temp = File(applicationContext.cacheDir, "downloads/${profile.id}/$id.source")
            temp.parentFile!!.mkdirs()
            val root =
                File(
                        applicationContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                            ?: applicationContext.filesDir,
                        "downloads/${profile.id}",
                    )
                    .apply { mkdirs() }
            val staging = File(root, "$id.part.mp3")
            var exported: DocumentFile? = null
            var privateOutput: File? = null
            var completed = false
            var entry = DownloadEntry(id.toString(), track, "queued")
            fun update(status: String, value: Int, error: String = "") {
                if (isStopped && status != "cancelled") return
                entry = entry.copy(status = status, progress = value, error = error)
                profile.library.download(entry)
            }
            try {
                setForeground(foreground(track.title, 0))
                update("downloading", 0)
                val resolved =
                    if (track.cid > 0) track
                    else
                        profile.api.video(VideoInput(track.bvid, track.page)).parts.firstOrNull {
                            it.page == track.page
                        } ?: error("所选分 P 不存在")
                entry = entry.copy(track = resolved)
                val sources = profile.api.audio(resolved, profile.account?.vip == true)
                val quality = inputData.getString("quality") ?: "标准"
                val source = sources.firstOrNull { it.quality == quality } ?: sources.first()
                if (source.quality.startsWith("Hi-Res") && profile.account?.vip != true)
                    throw ApiFailure(-1, "Hi-Res 需要大会员权益")
                var last = 0L
                AudioDownloader().download(listOf(source.url) + source.backups, temp) { count, total
                    ->
                    if (!isStopped && System.currentTimeMillis() - last > 700) {
                        val percent =
                            if (total > 0) (count * 65 / total).toInt().coerceIn(0, 65) else 0
                        update("downloading", percent)
                        setProgressAsync(workDataOf("progress" to percent))
                        last = System.currentTimeMillis()
                    }
                }
                ensureActive()
                check(temp.length() > 0) { "未能下载音源" }
                update("encoding", 65)
                Mp3Transcoder.transcode(temp, staging, bitrate) { p ->
                    if (System.currentTimeMillis() - last > 700) {
                        update("encoding", 65 + p * 30 / 100)
                        last = System.currentTimeMillis()
                    }
                }
                ensureActive()
                val target = File(root, DownloadNames.privateFile(resolved, id))
                java.nio.file.Files.move(
                    staging.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
                privateOutput = target
                var uri = Uri.fromFile(target).toString()
                var exportError = ""
                val tree = profile.library.get("download_tree")
                if (tree.isNotEmpty()) {
                    try {
                        val folder =
                            DocumentFile.fromTreeUri(applicationContext, Uri.parse(tree))
                                ?: error("文件夹不可用")
                        exported = folder.createFile("audio/mpeg", target.name) ?: error("无法创建文件")
                        applicationContext.contentResolver
                            .openOutputStream(exported!!.uri, "w")!!
                            .use { output -> target.inputStream().use { it.copyTo(output) } }
                        uri = exported!!.uri.toString()
                        target.delete()
                        privateOutput = null
                    } catch (e: Exception) {
                        exported?.delete()
                        exported = null
                        exportError = "已保存到应用目录，所选文件夹导出失败"
                    }
                }
                entry =
                    entry.copy(
                        track = resolved.copy(localUri = uri),
                        status = "complete",
                        progress = 100,
                        uri = uri,
                        exportUri = exported?.uri?.toString().orEmpty(),
                        error = exportError,
                    )
                ensureActive()
                profile.library.commitDownload(entry)
                completed = true
                runCatching {
                    val subs = profile.api.subtitleTracks(resolved)
                    val selected = SubtitlePolicy.choose(subs, resolved.title)
                    if (selected != null) {
                        val lines = profile.api.subtitle(selected)
                        profile.library.put(
                            "lyrics:${resolved.key}",
                            org.json
                                .JSONArray()
                                .apply {
                                    lines.forEach {
                                        put(
                                            org.json
                                                .JSONObject()
                                                .put("from", it.from)
                                                .put("to", it.to)
                                                .put("content", it.content)
                                        )
                                    }
                                }
                                .toString(),
                        )
                    }
                }
                Result.success()
            } catch (e: CancellationException) {
                update("cancelled", entry.progress)
                throw e
            } catch (e: Exception) {
                update(
                    "failed",
                    entry.progress,
                    if (e is ApiFailure) e.message.orEmpty() else "下载失败，请重试",
                )
                Result.failure()
            } finally {
                temp.delete()
                staging.delete()
                if (!completed) {
                    exported?.delete()
                    privateOutput?.delete()
                }
            }
        }

    private fun foreground(title: String, progress: Int): ForegroundInfo {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("downloads", "音乐下载", NotificationManager.IMPORTANCE_LOW)
        )
        val notification =
            NotificationCompat.Builder(applicationContext, "downloads")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText("正在下载音乐")
                .setOngoing(true)
                .setProgress(100, progress, progress == 0)
                .addAction(
                    0,
                    "取消",
                    WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
                )
                .build()
        return ForegroundInfo(
            id.hashCode(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}

object Downloads {
    fun enqueue(context: Context, profile: Profile, track: Track, quality: String, bitrate: Int) {
        val request =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(
                    workDataOf(
                        "profile" to profile.id,
                        "track" to TrackJson.encode(track).toString(),
                        "quality" to quality,
                        "bitrate" to bitrate,
                    )
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .addTag("profile:${profile.id}")
                .build()
        profile.library.download(DownloadEntry(request.id.toString(), track, "queued"))
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "download:${profile.id}:${track.bvid}:${track.page}",
                ExistingWorkPolicy.KEEP,
                request,
            )
    }
}
