package com.meni.whitelistfilter.net

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/** תור בתים פשוט: מוסיפים בסוף, מורידים מההתחלה */
class ByteQueue(initial: Int = 8192) {
    private var buf = ByteArray(initial)
    private var start = 0
    private var end = 0

    val size: Int get() = end - start
    val bytes: ByteArray get() = buf
    val head: Int get() = start

    fun append(src: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        ensure(len)
        System.arraycopy(src, off, buf, end, len)
        end += len
    }

    fun drop(n: Int) {
        start += n.coerceIn(0, size)
        if (start == end) {
            start = 0
            end = 0
        }
    }

    fun writeTo(ch: SocketChannel): Int {
        if (size == 0) return 0
        val n = ch.write(ByteBuffer.wrap(buf, start, size))
        drop(n)
        return n
    }

    private fun ensure(n: Int) {
        if (end + n <= buf.size) return
        val sz = size
        if (sz + n <= buf.size) {
            System.arraycopy(buf, start, buf, 0, sz)
        } else {
            val nb = ByteArray(maxOf(buf.size * 2, sz + n))
            System.arraycopy(buf, start, nb, 0, sz)
            buf = nb
        }
        start = 0
        end = sz
    }
}
