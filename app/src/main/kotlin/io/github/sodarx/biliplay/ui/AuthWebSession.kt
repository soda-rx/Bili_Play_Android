package io.github.sodarx.biliplay.ui

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.sodarx.biliplay.core.ExclusiveLeaseGate
import java.util.UUID

/** Login-only browser storage. It is never used for other app web content. */
class AuthWebSession(context: Context) {
    companion object {
        private val legacyGate = ExclusiveLeaseGate()
    }

    private val supported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    private var lease: ExclusiveLeaseGate.Lease? = null
    private var closed = false

    suspend fun acquire(): Boolean {
        if (closed) return false
        if (lease != null) return true
        if (!supported) {
            val acquired = legacyGate.acquire()
            if (closed) {
                acquired.close()
                return false
            }
            lease = acquired
        }
        return true
    }

    private val name = "biliplay_auth_" + UUID.randomUUID().toString().replace("-", "")
    private val pending = context.getSharedPreferences("auth_browser_cleanup", Context.MODE_PRIVATE)
    private var manager: CookieManager? = null
    private var storage: WebStorage? = null

    init {
        if (supported) {
            val store = ProfileStore.getInstance()
            val remaining =
                pending.getStringSet("profiles", emptySet()).orEmpty().filter { old ->
                    !runCatching { store.deleteProfile(old) }.isSuccess
                }
            pending.edit().putStringSet("profiles", remaining.toSet()).apply()
        }
    }

    fun attach(view: WebView): CookieManager {
        check(!closed && (supported || lease != null))
        if (supported) {
            WebViewCompat.setProfile(view, name)
            val profile = WebViewCompat.getProfile(view)
            manager = profile.cookieManager
            storage = profile.webStorage
            pending
                .edit()
                .putStringSet(
                    "profiles",
                    pending.getStringSet("profiles", emptySet()).orEmpty() + name,
                )
                .apply()
        } else {
            manager = CookieManager.getInstance()
            storage = WebStorage.getInstance()
        }
        return manager!!
    }

    fun cookies() = manager?.getCookie("https://www.bilibili.com/").orEmpty()

    fun close() {
        if (closed) return
        closed = true
        fun release() {
            lease?.close()
            lease = null
        }
        runCatching { storage?.deleteAllData() }
        try {
            val cookies = manager
            if (cookies == null) release()
            else
                cookies.removeAllCookies {
                    try {
                        cookies.flush()
                    } finally {
                        release()
                    }
                }
        } catch (_: Exception) {
            release()
        }
    }
}
