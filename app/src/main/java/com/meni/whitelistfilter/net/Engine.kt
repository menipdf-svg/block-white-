package com.meni.whitelistfilter.net

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.Rules
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue

interface ChannelHandler {
    fun onKey(key: SelectionKey)
}

/**
 * כל התעבורה של המכשיר נכנסת לכאן כחבילות IP.
 * לכל חיבור אנחנו פותחים חיבור אמיתי משלנו (מוגן מה-VPN) ומעבירים את הנתונים בשני הכיוונים —
 * רק אם החוקים מאפשרים לאפליקציה הזו להגיע ליעד הזה.
 */
class Engine(val vpn: VpnService, private val pfd: ParcelFileDescriptor) {

    companion object {
        val UPSTREAM_DNS: InetAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        const val MTU = 1500
    }

    @Volatile private var running = true
    val selector: Selector = Selector.open()
    private val incoming = ConcurrentLinkedQueue<ByteArray>()
    private val out = FileOutputStream(pfd.fileDescriptor)
    private val cm = vpn.getSystemService(ConnectivityManager::class.java)

    val tcp = HashMap<String, TcpSession>()
    val udp = HashMap<String, UdpSession>()
    val dns = HashSet<DnsSession>()
    val readBuf: ByteBuffer = ByteBuffer.allocate(65536)

    private var lastTick = 0L
    private var lastCacheCleanup = 0L

    private val reader = Thread({ readLoop() }, "tun-reader")
    private val loop = Thread({ mainLoop() }, "tun-engine")

    fun start() {
        reader.start()
        loop.start()
    }

    fun stop() {
        running = false
        try {
            selector.wakeup()
        } catch (_: Exception) {
        }
    }

    fun now() = System.currentTimeMillis()

    // ---------- קריאה מה-VPN ----------

    private fun readLoop() {
        val fd = pfd.fileDescriptor
        val input = FileInputStream(fd)
        val buf = ByteArray(65535)
        val pollFd = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLIN.toShort()
        }
        while (running) {
            try {
                pollFd.revents = 0
                if (Os.poll(arrayOf(pollFd), 1000) <= 0) continue
                val n = input.read(buf)
                if (n > 0) {
                    incoming.add(buf.copyOf(n))
                    selector.wakeup()
                }
            } catch (e: Exception) {
                if (!running) break
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun writeTun(packet: ByteArray) {
        try {
            out.write(packet)
        } catch (_: IOException) {
        }
    }

    // ---------- לולאה ראשית ----------

    private fun mainLoop() {
        try {
            while (running) {
                selector.select(500)
                while (true) {
                    val p = incoming.poll() ?: break
                    try {
                        handlePacket(p)
                    } catch (_: Exception) {
                    }
                }
                val it = selector.selectedKeys().iterator()
                while (it.hasNext()) {
                    val key = it.next()
                    it.remove()
                    if (!key.isValid) continue
                    try {
                        (key.attachment() as? ChannelHandler)?.onKey(key)
                    } catch (_: Exception) {
                    }
                }
                val t = now()
                if (t - lastTick >= 500) {
                    lastTick = t
                    tick(t)
                }
            }
        } catch (_: Exception) {
        } finally {
            ArrayList(tcp.values).forEach { it.close() }
            ArrayList(udp.values).forEach { it.close() }
            ArrayList(dns).forEach { it.close() }
            try {
                selector.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun tick(t: Long) {
        ArrayList(tcp.values).forEach { it.tick(t) }
        ArrayList(udp.values).forEach { if (t - it.lastActive > 120_000) it.close() }
        ArrayList(dns).forEach { if (t - it.created > 10_000) it.close() }
        if (t - lastCacheCleanup > 600_000) {
            lastCacheCleanup = t
            DnsCache.cleanup()
        }
    }

    // ---------- זיהוי האפליקציה ----------

    fun ownerUid(protocol: Int, p: Packet): Int = try {
        cm.getConnectionOwnerUid(
            protocol,
            InetSocketAddress(Packets.toInet(p.src), p.srcPort),
            InetSocketAddress(Packets.toInet(p.dst), p.dstPort)
        )
    } catch (_: Exception) {
        -1
    }

    private fun logBlocked(uid: Int, ip: Int, domains: Set<String>) {
        val d = domains.firstOrNull()
        if (d != null) FilterState.block(uid, d, true)
        else FilterState.block(uid, Packets.ipString(ip), false)
    }

    // ---------- טיפול בחבילות ----------

    private fun handlePacket(raw: ByteArray) {
        val p = Packets.parse(raw) ?: return
        when (p.protocol) {
            Packets.TCP -> handleTcp(p)
            Packets.UDP -> handleUdp(p)
        }
    }

    private fun handleTcp(p: Packet) {
        val key = "${p.srcPort}:${p.dst}:${p.dstPort}"
        val s = tcp[key]
        if (s != null) {
            s.onPacket(p)
            return
        }
        if ((p.flags and Packets.RST) != 0) return
        if ((p.flags and Packets.SYN) == 0 || (p.flags and Packets.ACK) != 0) {
            sendReset(p)
            return
        }
        val uid = ownerUid(OsConstants.IPPROTO_TCP, p)
        val domains = DnsCache.domains(p.dst)
        if (!Rules.connectionAllowed(uid, domains)) {
            logBlocked(uid, p.dst, domains)
            sendReset(p)
            return
        }
        val session = TcpSession(this, key, uid, p)
        tcp[key] = session
        session.open()
    }

    private fun sendReset(p: Packet) {
        val pkt = if ((p.flags and Packets.ACK) != 0) {
            Packets.tcp(p.dst, p.src, p.dstPort, p.srcPort, p.ack, 0, Packets.RST, 0)
        } else {
            var len = p.payload.size
            if ((p.flags and Packets.SYN) != 0) len++
            if ((p.flags and Packets.FIN) != 0) len++
            Packets.tcp(p.dst, p.src, p.dstPort, p.srcPort, 0, p.seq + len, Packets.RST or Packets.ACK, 0)
        }
        writeTun(pkt)
    }

    private fun handleUdp(p: Packet) {
        if (p.dstPort == 53) {
            handleDns(p)
            return
        }
        // QUIC חסום — הדפדפן חוזר ל-HTTPS רגיל, שאותו אפשר לבדוק לפי שם האתר
        if (p.dstPort == 443) return

        val key = "${p.srcPort}:${p.dst}:${p.dstPort}"
        var s = udp[key]
        if (s == null) {
            val uid = ownerUid(OsConstants.IPPROTO_UDP, p)
            val domains = DnsCache.domains(p.dst)
            val allowed = Rules.connectionAllowed(uid, domains)
            if (!allowed) logBlocked(uid, p.dst, domains)
            s = UdpSession(this, key, p, allowed)
            udp[key] = s
            if (allowed) s.open()
        }
        s.send(p.payload)
    }

    private fun handleDns(p: Packet) {
        val q = Dns.parseQuestion(p.payload) ?: return
        val uid = ownerUid(OsConstants.IPPROTO_UDP, p)
        if (!Rules.dnsAllowed(uid, q.name)) {
            FilterState.block(uid, q.name, true)
            writeTun(Packets.udp(p.dst, p.src, p.dstPort, p.srcPort, Dns.reply(p.payload, q, 3)))
            return
        }
        // אין IPv6 — מחזירים "אין כתובות" כדי שהאפליקציה תשתמש ב-IPv4
        if (q.type == Dns.TYPE_AAAA) {
            writeTun(Packets.udp(p.dst, p.src, p.dstPort, p.srcPort, Dns.reply(p.payload, q, 0)))
            return
        }
        try {
            val ch = DatagramChannel.open()
            vpn.protect(ch.socket())
            ch.configureBlocking(false)
            ch.connect(InetSocketAddress(UPSTREAM_DNS, 53))
            ch.write(ByteBuffer.wrap(p.payload))
            val s = DnsSession(this, p, q, ch)
            ch.register(selector, SelectionKey.OP_READ, s)
            dns += s
        } catch (_: Exception) {
        }
    }
}

// ======================= DNS =======================

class DnsSession(
    private val engine: Engine,
    private val query: Packet,
    private val question: Dns.Question,
    private val channel: DatagramChannel
) : ChannelHandler {
    val created = System.currentTimeMillis()

    override fun onKey(key: SelectionKey) {
        if (!key.isReadable) return
        val buf = engine.readBuf
        buf.clear()
        val n = try {
            channel.read(buf)
        } catch (_: IOException) {
            -1
        }
        if (n > 0) {
            val resp = buf.array().copyOf(n)
            for (ip in Dns.answerIps(resp)) DnsCache.put(ip, question.name)
            if (n + 28 <= Engine.MTU) {
                engine.writeTun(Packets.udp(query.dst, query.src, query.dstPort, query.srcPort, resp))
            }
        }
        close()
    }

    fun close() {
        try {
            channel.close()
        } catch (_: Exception) {
        }
        engine.dns -= this
    }
}

// ======================= UDP =======================

class UdpSession(
    private val engine: Engine,
    private val key: String,
    first: Packet,
    private val allowed: Boolean
) : ChannelHandler {
    private val appIp = first.src
    private val appPort = first.srcPort
    private val remoteIp = first.dst
    private val remotePort = first.dstPort
    private var channel: DatagramChannel? = null
    var lastActive = System.currentTimeMillis()

    fun open() {
        try {
            val ch = DatagramChannel.open()
            engine.vpn.protect(ch.socket())
            ch.configureBlocking(false)
            ch.connect(InetSocketAddress(Packets.toInet(remoteIp), remotePort))
            ch.register(engine.selector, SelectionKey.OP_READ, this)
            channel = ch
        } catch (_: Exception) {
            close()
        }
    }

    fun send(data: ByteArray) {
        lastActive = System.currentTimeMillis()
        if (!allowed) return
        try {
            channel?.write(ByteBuffer.wrap(data))
        } catch (_: Exception) {
            close()
        }
    }

    override fun onKey(key: SelectionKey) {
        if (!key.isReadable) return
        val ch = channel ?: return
        val buf = engine.readBuf
        while (true) {
            buf.clear()
            val n = try {
                ch.read(buf)
            } catch (_: IOException) {
                close()
                return
            }
            if (n <= 0) break
            lastActive = System.currentTimeMillis()
            if (n + 28 <= Engine.MTU) {
                engine.writeTun(Packets.udp(remoteIp, appIp, remotePort, appPort, buf.array(), n))
            }
        }
    }

    fun close() {
        try {
            channel?.close()
        } catch (_: Exception) {
        }
        channel = null
        engine.udp.remove(key)
    }
}
