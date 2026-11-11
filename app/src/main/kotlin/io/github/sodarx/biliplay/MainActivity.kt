package io.github.sodarx.biliplay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import io.github.sodarx.biliplay.core.PlaybackLaunch
import io.github.sodarx.biliplay.ui.BiliPlayRoot
import io.github.sodarx.biliplay.ui.BiliPlayViewModel

class MainActivity : ComponentActivity() {
    private val model by viewModels<BiliPlayViewModel>()
    private var sharedText by mutableStateOf<String?>(null)

    private fun acceptIntent(value: Intent, openPlayer: Boolean = true) {
        sharedText =
            if (value.action == Intent.ACTION_SEND && value.type == "text/plain")
                value.getStringExtra(Intent.EXTRA_TEXT)
            else null
        if (openPlayer && value.action == PlaybackLaunch.ACTION) model.openPlayerFromSystem()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        val screen =
            if (android.os.Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        if (screen != null) {
            val current = screen.mode
            val modes = screen.supportedModes
            val index =
                io.github.sodarx.biliplay.core.DisplayRatePolicy.best(
                    current.physicalWidth,
                    current.physicalHeight,
                    modes.map {
                        io.github.sodarx.biliplay.core.DisplayRateOption(
                            it.physicalWidth,
                            it.physicalHeight,
                            it.refreshRate,
                        )
                    },
                )
            if (index != null)
                window.attributes =
                    window.attributes.apply {
                        preferredDisplayModeId = modes[index].modeId
                        preferredRefreshRate = modes[index].refreshRate
                    }
        }
    }

    override fun onStop() {
        (application as BiliPlayApplication).playback.checkpoint()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        acceptIntent(intent, openPlayer = savedInstanceState == null)
        setContent { BiliPlayRoot(sharedText, model) }
    }
}
