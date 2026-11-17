package io.github.sodarx.biliplay

import android.app.Application
import io.github.sodarx.biliplay.data.AppRepository
import io.github.sodarx.biliplay.playback.PlaybackHub

class BiliPlayApplication : Application() {
    val repository by lazy { AppRepository(this) }
    val playback by lazy { PlaybackHub(repository) }
    val updates by lazy { io.github.sodarx.biliplay.update.AppUpdateManager(this) }
}
