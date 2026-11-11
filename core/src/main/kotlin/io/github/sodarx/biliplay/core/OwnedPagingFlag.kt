package io.github.sodarx.biliplay.core

data class OwnedPagingFlag(private val owner: Any? = null, val paging: Boolean = false) {
    fun ownedBy(candidate: Any): Boolean = owner === candidate

    fun claim(candidate: Any): OwnedPagingFlag =
        if (ownedBy(candidate)) this else OwnedPagingFlag(candidate)

    fun update(candidate: Any, value: Boolean): OwnedPagingFlag =
        if (ownedBy(candidate)) copy(paging = value) else this

    fun release(candidate: Any): OwnedPagingFlag =
        if (ownedBy(candidate)) OwnedPagingFlag() else this
}
