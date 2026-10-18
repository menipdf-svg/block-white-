package com.meni.whitelistfilter.net

import java.net.InetAddress

/** חבילת IPv4 מפוענחת (TCP או UDP) */
class Packet(
    val protocol: Int,
    val src: Int,
    val dst: Int,
    val srcPort: Int,
    val dstPort: Int,
    val seq: Int,
    val ack: Int,
    val flags: Int,
    val window: Int,
    val mss: Int,
    val payload: ByteArray
)

object Packets {
    const val TCP = 6
    const val UDP = 17

    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    private var ipId = 0

    fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
    fun u16(b: ByteArray, o: Int): Int = (u8(b, o) shl 8) or u8(b, o + 1)
    fun u32(b: ByteArray, o: Int): Int = (u16(b, o) shl 16) or u16(b, o + 2)

    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte()
        b[o + 1] = v.toByte()
    }

    fun put32(b: ByteArray, o: Int, v: Int) {
        put16(b, o, v ushr 16)
        put16(b, o + 2, v and 0xFFFF)
    }

    fun toInet(ip: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf((ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte())
    )

    fun ipString(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    fun parse(b: ByteArray): Packet? {
        if (b.size < 20) return null
        if (((b[0].toInt() ushr 4) and 0xF) != 4) return null
        val ihl = (b[0].toInt() and 0xF) * 4
        val total = minOf(u16(b, 2), b.size)
        if (total < ihl) return null
        if ((u16(b, 6) and 0x3FFF) != 0) return null // מקטעים — לא נתמך
        val proto = u8(b, 9)
        val src = u32(b, 12)
        val dst = u32(b, 16)

        when (proto) {
            TCP -> {
                val t = ihl
                if (total < t + 20) return null
                val off = (u8(b, t + 12) ushr 4) * 4
                if (off < 20 || t + off > total) return null
                var mss = 0
                var o = t + 20
                val end = t + off
                while (o < end) {
                    val kind = u8(b, o)
                    if (kind == 0) break
                    if (kind == 1) {
                        o++
                        continue
                    }
                    if (o + 1 >= end) break
                    val l = u8(b, o + 1)
                    if (l < 2) break
                    if (kind == 2 && l == 4 && o + 3 < end) mss = u16(b, o + 2)
                    o += l
                }
                return Packet(
                    TCP, src, dst, u16(b, t), u16(b, t + 2), u32(b, t + 4), u32(b, t + 8),
                    u8(b, t + 13), u16(b, t + 14), mss, b.copyOfRange(t + off, total)
                )
            }
            UDP -> {
                val t = ihl
                if (total < t + 8) return null
                return Packet(
                    UDP, src, dst, u16(b, t), u16(b, t + 2), 0, 0, 0, 0, 0,
                    b.copyOfRange(t + 8, total)
                )
            }
            else -> return null
        }
    }

    private fun checksumAdd(start: Long, b: ByteArray, off: Int, len: Int): Long {
        var s = start
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += u16(b, i)
            i += 2
        }
        if (i < end) s += (u8(b, i) shl 8)
        return s
    }

    private fun fold(sum: Long): Int {
        var s = sum
        while ((s ushr 16) != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    private fun ipHeader(p: ByteArray, total: Int, proto: Int, src: Int, dst: Int) {
        p[0] = 0x45
        put16(p, 2, total)
        ipId = (ipId + 1) and 0xFFFF
        put16(p, 4, ipId)
        p[6] = 0x40
        p[8] = 64
        p[9] = proto.toByte()
        put32(p, 12, src)
        put32(p, 16, dst)
        put16(p, 10, fold(checksumAdd(0, p, 0, 20)))
    }

    private fun l4Checksum(p: ByteArray, proto: Int, src: Int, dst: Int, off: Int, len: Int): Int {
        var s = 0L
        s += (src ushr 16)
        s += (src and 0xFFFF)
        s += (dst ushr 16)
        s += (dst and 0xFFFF)
        s += proto
        s += len
        return fold(checksumAdd(s, p, off, len))
    }

    fun tcp(
        src: Int, dst: Int, srcPort: Int, dstPort: Int,
        seq: Int, ack: Int, flags: Int, window: Int,
        data: ByteArray? = null, dataOff: Int = 0, dataLen: Int = 0,
        mss: Int = 0
    ): ByteArray {
        val optLen = if (mss > 0) 4 else 0
        val tcpLen = 20 + optLen + dataLen
        val total = 20 + tcpLen
        val p = ByteArray(total)
        ipHeader(p, total, TCP, src, dst)
        val t = 20
        put16(p, t, srcPort)
        put16(p, t + 2, dstPort)
        put32(p, t + 4, seq)
        put32(p, t + 8, ack)
        p[t + 12] = (((20 + optLen) / 4) shl 4).toByte()
        p[t + 13] = flags.toByte()
        put16(p, t + 14, window.coerceIn(0, 65535))
        if (mss > 0) {
            p[t + 20] = 2
            p[t + 21] = 4
            put16(p, t + 22, mss)
        }
        if (dataLen > 0 && data != null) System.arraycopy(data, dataOff, p, t + 20 + optLen, dataLen)
        put16(p, t + 16, l4Checksum(p, TCP, src, dst, t, tcpLen))
        return p
    }

    fun udp(src: Int, dst: Int, srcPort: Int, dstPort: Int, data: ByteArray, dataLen: Int = data.size): ByteArray {
        val udpLen = 8 + dataLen
        val total = 20 + udpLen
        val p = ByteArray(total)
        ipHeader(p, total, UDP, src, dst)
        val t = 20
        put16(p, t, srcPort)
        put16(p, t + 2, dstPort)
        put16(p, t + 4, udpLen)
        System.arraycopy(data, 0, p, t + 8, dataLen)
        var c = l4Checksum(p, UDP, src, dst, t, udpLen)
        if (c == 0) c = 0xFFFF
        put16(p, t + 6, c)
        return p
    }
}
