package io.github.sodarx.biliplay.core

import java.net.URLEncoder
import java.security.MessageDigest

object WbiSigner {
    private val order =
        intArrayOf(
            46,
            47,
            18,
            2,
            53,
            8,
            23,
            32,
            15,
            50,
            10,
            31,
            58,
            3,
            45,
            35,
            27,
            43,
            5,
            49,
            33,
            9,
            42,
            19,
            29,
            28,
            14,
            39,
            12,
            38,
            41,
            13,
            37,
            48,
            7,
            16,
            24,
            55,
            40,
            61,
            26,
            17,
            0,
            1,
            60,
            51,
            30,
            4,
            22,
            25,
            54,
            21,
            56,
            59,
            6,
            63,
            57,
            62,
            11,
            36,
            20,
            34,
            44,
            52,
        )

    private fun encode(value: String) =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")

    fun sign(parameters: Map<String, String>, image: String, sub: String, timestamp: Long): String {
        val raw = image + sub
        require(
            raw.length == 64 && raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        ) {
            "签名密钥无效"
        }
        val key = order.take(32).map { raw[it] }.joinToString("")
        val values =
            parameters.toMutableMap().apply {
                remove("w_rid")
                this["wts"] = timestamp.toString()
            }
        val query =
            values.toSortedMap().entries.joinToString("&") { (k, v) ->
                "${encode(k)}=${encode(v.filterNot { it in "!'()*" })}"
            }
        val digest =
            MessageDigest.getInstance("MD5")
                .digest((query + key).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$query&w_rid=$digest"
    }
}
