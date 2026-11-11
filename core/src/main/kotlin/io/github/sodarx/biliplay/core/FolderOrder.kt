package io.github.sodarx.biliplay.core

object FolderOrder {
    fun move(ids: List<Long>, from: Long, target: Long): List<Long> {
        if (from == target || from !in ids || target !in ids) return ids
        val result = ids.toMutableList()
        val index = result.indexOf(target)
        result.remove(from)
        result.add(index, from)
        return result
    }
}
