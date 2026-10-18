package com.meni.whitelistfilter.net

/** קורא את שם האתר (SNI) מתוך תחילת חיבור HTTPS */
object Sni {
    sealed class Result {
        object NeedMore : Result()
        object None : Result()
        class Host(val name: String) : Result()
    }

    private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int): Int = (u8(b, o) shl 8) or u8(b, o + 1)

    fun parse(b: ByteArray, off: Int, len: Int): Result {
        try {
            if (len < 1) return Result.NeedMore
            if (u8(b, off) != 0x16) return Result.None
            if (len < 5) return Result.NeedMore
            val recLen = u16(b, off + 3)
            if (recLen > 16384) return Result.None
            if (len < 5 + recLen) return Result.NeedMore
            val end = off + 5 + recLen
            var p = off + 5
            if (u8(b, p) != 1) return Result.None // לא ClientHello
            p += 4 // סוג + אורך
            p += 2 + 32 // גרסה + random
            p += 1 + u8(b, p) // session id
            p += 2 + u16(b, p) // cipher suites
            p += 1 + u8(b, p) // compression
            if (p + 2 > end) return Result.None
            val extEnd = minOf(p + 2 + u16(b, p), end)
            p += 2
            while (p + 4 <= extEnd) {
                val type = u16(b, p)
                val l = u16(b, p + 2)
                p += 4
                if (type == 0 && p + 5 <= extEnd) {
                    val nameLen = u16(b, p + 3)
                    if (u8(b, p + 2) == 0 && p + 5 + nameLen <= extEnd) {
                        return Result.Host(String(b, p + 5, nameLen, Charsets.US_ASCII).lowercase())
                    }
                    return Result.None
                }
                p += l
            }
            return Result.None
        } catch (_: Exception) {
            return Result.None
        }
    }
}
