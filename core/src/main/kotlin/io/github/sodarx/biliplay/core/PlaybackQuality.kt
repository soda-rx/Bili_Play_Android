package io.github.sodarx.biliplay.core

/**
 * The listener's quality choice is remembered per account. An empty preference means "always take
 * the best source this account can access", so a fresh install requests Hi-Res when the platform
 * offers it. A temporary lack of Hi-Res must never overwrite a stored preference.
 */
object PlaybackQuality {
    const val Standard = "标准"
    const val HiRes = "Hi-Res"

    /** No explicit choice: every track selects the highest source that is actually available. */
    const val Automatic = ""

    fun normalize(value: String): String =
        when (value) {
            HiRes -> HiRes
            Standard -> Standard
            else -> Automatic
        }

    fun select(sources: List<AudioSource>, preferred: String): Int {
        if (sources.isEmpty()) return 0
        val best =
            sources
                .indexOfFirst { it.quality == HiRes }
                .takeIf { it >= 0 }
                ?: sources.indexOfFirst { it.quality == Standard }.takeIf { it >= 0 }
                ?: 0
        return when (normalize(preferred)) {
            HiRes ->
                sources.indexOfFirst { it.quality == HiRes }.takeIf { it >= 0 } ?: best
            Standard ->
                sources.indexOfFirst { it.quality == Standard }.takeIf { it >= 0 } ?: best
            else -> best
        }
    }

    /** Name of the source [select] would choose, used for download defaults and saved state. */
    fun selectedName(sources: List<AudioSource>, preferred: String): String =
        sources.getOrNull(select(sources, preferred))?.quality.orEmpty()

    /** Preference to store after the listener picks a source from the quality dialog. */
    fun storedChoice(quality: String): String = normalize(quality)

    /** Default download quality before a track's actual sources are known. */
    fun downloadDefault(preferred: String, vip: Boolean): String =
        if (vip && normalize(preferred) != Standard) HiRes else Standard
}
