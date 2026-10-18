package com.meni.whitelistfilter.net

import com.meni.whitelistfilter.net.Packets.put16
import com.meni.whitelistfilter.net.Packets.u16
import com.meni.whitelistfilter.net.Packets.u32
import com.meni.whitelistfilter.net.Packets.u8
import java.util.concurrent.ConcurrentHashMap

object Dns {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    class Question(val name: String, val type: Int, val end: Int)

    fun parseQuestion(dns: ByteArray): Question? {
        try {
            if (dns.size < 17) return null
            if ((u8(dns, 2) and 0x80) != 0) return null // זו תשובה, לא שאילתה
            if (u16(dns, 4) < 1) return null
            var i = 12
            val labels = ArrayList<String>()
            while (true) {
                val len = u8(dns, i)
                if (len == 0) {
                    i++
                    break
                }
                if ((len and 0xC0) != 0 || i + 1 + len > dns.size) return null
                labels += String(dns, i + 1, len, Charsets.US_ASCII)
                i += len + 1
            }
            if (labels.isEmpty() || i + 4 > dns.size) return null
            return Question(labels.joinToString(".").lowercase(), u16(dns, i), i + 4)
        } catch (_: Exception) {
            return null
        }
    }

    /** תשובה מקומית בלי רשומות. rcode 3 = הדומיין לא קיים, rcode 0 = אין כתובות */
    fun reply(query: ByteArray, q: Question, rcode: Int): ByteArray {
        val r = query.copyOf(q.end)
        r[2] = ((r[2].toInt() and 0x79) or 0x80).toByte()
        r[3] = (0x80 or rcode).toByte()
        put16(r, 4, 1)
        for (i in 6 until 12) r[i] = 0
        return r
    }

    private fun skipName(b: ByteArray, start: Int): Int {
        var o = start
        while (true) {
            val len = u8(b, o)
            if (len == 0) return o + 1
            if ((len and 0xC0) == 0xC0) return o + 2
            o += len + 1
        }
    }

    /** כתובות IPv4 מתוך תשובת DNS */
    fun answerIps(resp: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        try {
            val qd = u16(resp, 4)
            val an = u16(resp, 6)
            var o = 12
            repeat(qd) { o = skipName(resp, o) + 4 }
            repeat(an) {
                o = skipName(resp, o)
                val type = u16(resp, o)
                val rdLen = u16(resp, o + 8)
                val rd = o + 10
                if (type == TYPE_A && rdLen == 4 && rd + 4 <= resp.size) out += u32(resp, rd)
                o = rd + rdLen
            }
        } catch (_: Exception) {
        }
        return out
    }
}

/** זוכר לאיזה דומיין שייכת כל כתובת IP, לפי תשובות ה-DNS שעברו דרכנו */
object DnsCache {
    private const val MAX_AGE = 24 * 60 * 60 * 1000L
    private val map = ConcurrentHashMap<Int, ConcurrentHashMap<String, Long>>()

    fun put(ip: Int, domain: String) {
        map.getOrPut(ip) { ConcurrentHashMap() }[domain] = System.currentTimeMillis()
    }

    fun domains(ip: Int): Set<String> = map[ip]?.keys?.toSet() ?: emptySet()

    fun cleanup() {
        val limit = System.currentTimeMillis() - MAX_AGE
        val iter = map.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            e.value.entries.removeIf { entry -> entry.value < limit }
            if (e.value.isEmpty()) iter.remove()
        }
    }
}
