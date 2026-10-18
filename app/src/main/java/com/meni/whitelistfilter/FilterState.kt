package com.meni.whitelistfilter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

data class BlockEntry(
    val pkg: String?,
    val label: String,
    val target: String,
    val isDomain: Boolean,
    val time: Long
)

object FilterState {
    val running = MutableStateFlow(false)
    val log = MutableStateFlow<List<BlockEntry>>(emptyList())

    private val lastSeen = ConcurrentHashMap<String, Long>()

    fun block(uid: Int, target: String, isDomain: Boolean) {
        val pkg = Rules.packageForUid(uid)
        val key = "$pkg|$target"
        val now = System.currentTimeMillis()
        val prev = lastSeen[key]
        if (prev != null && now - prev < 3000) return
        lastSeen[key] = now
        if (lastSeen.size > 2000) lastSeen.clear()

        val entry = BlockEntry(pkg, Rules.labelForUid(uid), target, isDomain, now)
        log.update { list ->
            (listOf(entry) + list.filterNot { it.pkg == pkg && it.target == target }).take(200)
        }
    }

    fun clearLog() {
        log.value = emptyList()
        lastSeen.clear()
    }
}
