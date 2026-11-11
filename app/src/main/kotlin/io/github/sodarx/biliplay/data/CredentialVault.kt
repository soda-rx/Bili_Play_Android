package io.github.sodarx.biliplay.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.sodarx.biliplay.core.CookieJson
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray

fun validProfile(id: String): String {
    require(Regex("[A-Za-z0-9_-]{1,80}").matches(id))
    return id
}

class CredentialVault(private val context: Context) {
    private fun file(id: String) =
        File(context.filesDir, "profiles/${validProfile(id)}/session.enc")

    private fun alias(id: String) = "${context.packageName}.credentials.${validProfile(id)}"

    private fun key(id: String): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias(id), null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                            alias(id),
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generateKey()
            }
    }

    @Synchronized
    fun write(id: String, cookies: List<Cookie>) {
        val target = file(id)
        if (cookies.isEmpty()) {
            AtomicFile(target).delete()
            return
        }
        target.parentFile!!.mkdirs()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(id))
        cipher.updateAAD((context.packageName + ":" + id).toByteArray())
        val plain = CookieJson.encode(cookies).toByteArray()
        val bytes = byteArrayOf(2, cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain)
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    fun read(id: String): List<Cookie> {
        val f = file(id)
        if (!f.exists()) return emptyList()
        return runCatching {
                val bytes = AtomicFile(f).readFully()
                require(bytes.size > 30 && bytes[0].toInt() in 1..2)
                val size = bytes[1].toInt() and 255
                require(size == 12 && bytes.size > 2 + size + 16)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(
                    Cipher.DECRYPT_MODE,
                    key(id),
                    GCMParameterSpec(128, bytes.copyOfRange(2, 2 + size)),
                )
                c.updateAAD((context.packageName + ":" + id).toByteArray())
                val plain =
                    c.doFinal(bytes.copyOfRange(2 + size, bytes.size)).toString(Charsets.UTF_8)
                if (bytes[0].toInt() == 2) CookieJson.decode(plain)
                else {
                    val a = JSONArray(plain)
                    (0 until a.length()).mapNotNull {
                        Cookie.parse("https://www.bilibili.com/".toHttpUrl(), a.getString(it))
                    }
                }
            }
            .getOrDefault(emptyList())
    }

    fun delete(id: String) {
        AtomicFile(file(id)).delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias(id))
    }
}
