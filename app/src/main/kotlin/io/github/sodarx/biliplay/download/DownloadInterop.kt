package io.github.sodarx.biliplay.download

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import io.github.sodarx.biliplay.data.DownloadEntry
import io.github.sodarx.biliplay.data.validProfile
import java.io.File

object DownloadInterop {
    fun open(context: Context, entry: DownloadEntry, profileId: String, share: Boolean) {
        require(entry.status == "complete")
        val raw = Uri.parse(entry.uri)
        val uri =
            when (raw.scheme) {
                "content" -> raw
                "file" -> {
                    val file = File(requireNotNull(raw.path)).canonicalFile
                    val base =
                        File(
                                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                                    ?: context.filesDir,
                                "downloads/${validProfile(profileId)}",
                            )
                            .canonicalFile
                    require(file.path.startsWith(base.path + File.separator) && file.isFile)
                    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                }
                else -> error("下载文件不可用")
            }
        val intent =
            Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                if (share) {
                    type = "audio/mpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, entry.track.title)
                } else setDataAndType(uri, "audio/mpeg")
                clipData = ClipData.newRawUri(entry.track.title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(intent, if (share) "分享音乐" else "打开音乐"))
    }
}
