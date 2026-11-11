package io.github.sodarx.biliplay.data

import android.content.Context
import io.github.sodarx.biliplay.core.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ProfileInfo(val id: String, val account: Account?, val canDelete: Boolean = false)

class Profile(
    val id: String,
    var account: Account?,
    val api: BilibiliApi,
    library: () -> LocalLibrary,
    val preferences: AppPreferences,
) {
    val library by lazy(library)
}

class AppRepository(private val context: Context) {
    private val vault = CredentialVault(context)
    private val index = context.getSharedPreferences("profile_index", Context.MODE_PRIVATE)
    private val handles = ConcurrentHashMap<String, Profile>()
    private val libraries = ConcurrentHashMap<String, LocalLibrary>()
    private val mutable =
        MutableStateFlow(
            open(
                index.getString("active", "guest")?.takeIf {
                    Regex("[A-Za-z0-9_-]{1,80}").matches(it)
                } ?: "guest"
            )
        )
    val profile = mutable.asStateFlow()

    fun profiles(): List<ProfileInfo> = runCatching {
        val a = JSONArray(index.getString("profiles", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            ProfileInfo(
                validProfile(o.getString("id")),
                o.optJSONObject("account")?.let {
                    Account(
                        it.optLong("id"),
                        it.optString("name"),
                        it.optString("avatar"),
                        it.optBoolean("vip"),
                        it.optInt("level").coerceIn(0, 6),
                    )
                },
            )
        }
    }
        .getOrDefault(emptyList())
        .let {
            listOf(ProfileInfo("guest", null)) +
                it.filterNot { p -> p.id == "guest" }
                    .map { p -> p.copy(canDelete = canRemoveProfile(p.id)) }
        }

    @Synchronized
    fun open(id: String): Profile =
        handles.getOrPut(validProfile(id)) {
            val jar = SessionCookies { vault.write(id, it) }.apply { restore(vault.read(id)) }
            Profile(
                id,
                profiles().firstOrNull { it.id == id }?.account,
                BilibiliApi(sessions = jar),
                { libraries.computeIfAbsent(id) { LocalLibrary(context, it) } },
                AppPreferences(context, id),
            )
        }

    fun newLogin(): Profile = open("login_" + UUID.randomUUID().toString().replace("-", ""))

    @Synchronized
    fun acceptLogin(candidate: Profile, account: Account) {
        val id = "uid_${account.id}"
        val firstLogin = profiles().none { it.id == id }
        handles.remove(id)?.let {
            it.api.sessions.invalidate()
            // Account-local library remains valid for in-flight save tasks.
        }
        vault.write(id, candidate.api.sessions.snapshot())
        val entries =
            profiles().filterNot { it.id == "guest" || it.id == id } + ProfileInfo(id, account)
        index
            .edit()
            .putString(
                "profiles",
                JSONArray()
                    .apply {
                        entries.forEach {
                            put(
                                JSONObject()
                                    .put("id", it.id)
                                    .put(
                                        "account",
                                        it.account?.let { a ->
                                            JSONObject()
                                                .put("id", a.id)
                                                .put("name", a.name)
                                                .put("avatar", a.avatar)
                                                .put("vip", a.vip)
                                                .put("level", a.level)
                                        },
                                    )
                            )
                        }
                    }
                    .toString(),
            )
            .apply()
        cancelLogin(candidate)
        if (firstLogin) open(id).preferences.write(open("guest").preferences.read())
        switch(id)
    }

    @Synchronized
    fun cancelLogin(candidate: Profile) {
        if (!candidate.id.startsWith("login_")) return
        candidate.api.sessions.clear()
        vault.delete(candidate.id)
        handles.remove(candidate.id)
        libraries.remove(candidate.id)?.close()
    }

    @Synchronized
    fun switch(id: String) {
        require(id == "guest" || profiles().any { it.id == id })
        val p = open(id)
        index.edit().putString("active", id).apply()
        mutable.value = p
    }

    fun loggedIn(p: Profile = profile.value) =
        p.account != null && p.api.sessions.value("SESSDATA") != null

    @Synchronized
    fun logout() {
        val p = profile.value
        p.api.sessions.clear()
        vault.delete(p.id)
        handles.remove(p.id)
        val newProfile = open(p.id)
        mutable.value = newProfile
    }

    @Synchronized
    fun markExpired(id: String) {
        val p = handles[id] ?: return
        p.api.sessions.clear()
        vault.delete(id)
        handles.remove(id)
        if (profile.value === p) mutable.value = open(id)
    }

    fun canRemoveProfile(id: String): Boolean = runCatching {
        val cookies = handles[id]?.api?.sessions?.snapshot() ?: vault.read(id)
        AccountRemoval.allowed(
            id == "guest",
            cookies.any { it.name == "SESSDATA" && it.expiresAt > System.currentTimeMillis() },
        )
    }
        .getOrDefault(false)

    @Synchronized
    fun removeProfile(id: String) {
        require(canRemoveProfile(id)) { "请先退出该账号后再删除" }
        if (profile.value.id == id) switch("guest")
        handles.remove(id)?.api?.sessions?.clear()
        vault.delete(id)
        val remaining = profiles().filterNot { it.id == id || it.id == "guest" }
        index
            .edit()
            .putString(
                "profiles",
                JSONArray()
                    .apply {
                        remaining.forEach {
                            put(
                                JSONObject()
                                    .put("id", it.id)
                                    .put(
                                        "account",
                                        it.account?.let { a ->
                                            JSONObject()
                                                .put("id", a.id)
                                                .put("name", a.name)
                                                .put("avatar", a.avatar)
                                                .put("vip", a.vip)
                                                .put("level", a.level)
                                        },
                                    )
                            )
                        }
                    }
                    .toString(),
            )
            .apply()
    }

    fun playerDemoDone() = index.getBoolean("player_media_demo_done", false)

    fun markPlayerDemoDone() {
        index.edit().putBoolean("player_media_demo_done", true).apply()
    }

    fun themeSetupComplete() = index.getBoolean("theme_setup_complete", false)

    fun finishThemeSetup() {
        index.edit().putBoolean("theme_setup_complete", true).apply()
    }

    fun agreementAccepted() = index.getInt("agreement_version", 0) >= 1

    fun acceptAgreement() {
        index.edit().putInt("agreement_version", 1).apply()
    }

    fun updateAccount(account: Account) {
        val p = profile.value
        p.account = account
        val rows =
            profiles()
                .filterNot { it.id == "guest" }
                .map { if (it.id == p.id) ProfileInfo(p.id, account) else it }
        index
            .edit()
            .putString(
                "profiles",
                JSONArray()
                    .apply {
                        rows.forEach {
                            put(
                                JSONObject()
                                    .put("id", it.id)
                                    .put(
                                        "account",
                                        it.account?.let { a ->
                                            JSONObject()
                                                .put("id", a.id)
                                                .put("name", a.name)
                                                .put("avatar", a.avatar)
                                                .put("vip", a.vip)
                                                .put("level", a.level)
                                        },
                                    )
                            )
                        }
                    }
                    .toString(),
            )
            .apply()
    }
}
