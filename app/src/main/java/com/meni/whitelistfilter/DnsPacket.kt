package com.meni.whitelistfilter

class DnsQuery(
    private val srcIp: ByteArray,
    private val dstIp: ByteArray,
    private val srcPort: Int,
    private val dstPort: Int,
    val dnsPayload: ByteArray,
    val domain: String,
    private val questionEnd: Int
) {
    // עוטף תשובת DNS בחבילת IP/UDP חזרה לאפליקציה ששאלה
    fun responsePacket(dns: ByteArray): ByteArray =
        DnsPacket.buildUdp(dstIp, srcIp, dstPort, srcPort, dns)

    // תשובת NXDOMAIN = "הדומיין לא קיים"
    fun blockedResponse(): ByteArray {
        val r = dnsPayload.copyOf(questionEnd)
        r[2] = ((r[2].toInt() and 0x79) or 0x80).toByte()
        r[3] = 0x83.toByte()
        for (i in 6 until 12) r[i] = 0
        return responsePacket(r)
    }
}

object DnsPacket {

    fun parse(p: ByteArray): DnsQuery? {
        if (p.size < 28) return null
        if (((p[0].toInt() shr 4) and 0xF) != 4) return null
        val ihl = (p[0].toInt() and 0xF) * 4
        if (p[9].toInt() != 17) return null // UDP בלבד
        val total = minOf(u16(p, 2), p.size)
        if (total < ihl + 8 + 12) return null

        val srcPort = u16(p, ihl)
        val dstPort = u16(p, ihl + 2)
        if (dstPort != 53) return null

        val dns = p.copyOfRange(ihl + 8, total)
        if (u16(dns, 4) < 1) return null

        var i = 12
        val labels = mutableListOf<String>()
        while (i < dns.size) {
            val len = dns[i].toInt() and 0xFF
            if (len == 0) {
                i++
                break
            }
            if ((len and 0xC0) != 0 || i + 1 + len > dns.size) return null
            labels += String(dns, i + 1, len, Charsets.US_ASCII)
            i += len + 1
        }
        val questionEnd = i + 4
        if (questionEnd > dns.size || labels.isEmpty()) return null

        return DnsQuery(
            srcIp = p.copyOfRange(12, 16),
            dstIp = p.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            dnsPayload = dns,
            domain = labels.joinToString(".").lowercase(),
            questionEnd = questionEnd
        )
    }

    fun buildUdp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        p[6] = 0x40
        p[8] = 64
        p[9] = 17
        src.copyInto(p, 12)
        dst.copyInto(p, 16)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put16(p, 24, 8 + payload.size)
        payload.copyInto(p, 28)
        return p
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len) {
            sum += u16(b, i)
            i += 2
        }
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte()
        b[o + 1] = v.toByte()
    }
}
