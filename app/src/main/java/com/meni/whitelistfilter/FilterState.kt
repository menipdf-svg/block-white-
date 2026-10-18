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

/** בקשה של אפליקציה ליעד מסוים: כמה פעמים, מתי לאחרונה, ומה הוחלט בפעם האחרונה */
data class RequestEntry(
    val target: String,
    val isDomain: Boolean,
    val allowed: Boolean,
    val count: Int,
    val last: Long
)

object FilterState {
    val running = MutableStateFlow(false)
    val log = MutableStateFlow<List<BlockEntry>>(emptyList())

    private val lastSeen = ConcurrentHashMap<String, Long>()

    /** לכל אפליקציה (לפי שם החבילה): כל היעדים שהיא ביקשה מאז שהמסנן עלה */
    private val requests = ConcurrentHashMap<String, ConcurrentHashMap<String, RequestEntry>>()
    private const val MAX_PER_APP = 500

    fun block(uid: Int, target: String, isDomain: Boolean) {
        request(uid, target, isDomain, false)

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

    fun request(uid: Int, target: String, isDomain: Boolean, allowed: Boolean) {
        val pkg = Rules.packageForUid(uid) ?: return
        val now = System.currentTimeMillis()
        val map = requests.getOrPut(pkg) { ConcurrentHashMap() }
        map.compute(target) { _, old ->
            RequestEntry(target, isDomain, allowed, (old?.count ?: 0) + 1, now)
        }
        if (map.size > MAX_PER_APP) {
            map.values.minByOrNull { it.last }?.let { map.remove(it.target) }
        }
    }

    /** היעדים של אפליקציה, החדשים ראשונים */
    fun requestsFor(pkg: String): List<RequestEntry> =
        requests[pkg]?.values?.sortedByDescending { it.last } ?: emptyList()

    fun clearRequests(pkg: String) {
        requests.remove(pkg)
    }

    fun clearLog() {
        log.value = emptyList()
        lastSeen.clear()
    }
}
