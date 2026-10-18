package com.meni.whitelistfilter.net

import com.meni.whitelistfilter.Mode
import java.io.Closeable
import java.io.DataInputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** מה שהשרת צריך משאר האפליקציה. בלי תלות באנדרואיד, כדי שאפשר יהיה לבדוק אותו גם על מחשב. */
interface ProxyPolicy {
    /** איזו אפליקציה (uid) פתחה את החיבור, או 1- אם לא ידוע */
    fun ownerUid(protocol: Int, src: InetSocketAddress, dst: InetSocketAddress): Int
    fun mode(uid: Int): Mode
    fun isAllowed(uid: Int, domain: String): Boolean
    fun blocked(uid: Int, target: String, isDomain: Boolean)
    /** חיבור שאושר — לרשימת היעדים של האפליקציה */
    fun passed(uid: Int, target: String, isDomain: Boolean) {}
    fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
}

/** כתובת יעד כפי שהיא מגיעה בפרוטוקול SOCKS5: שם דומיין או כתובת IP */
class Target(val domain: String?, val address: InetAddress?, val port: Int) {
    val label: String get() = domain ?: address?.hostAddress ?: "?"

    companion object {
        fun read(input: DataInputStream): Target = when (input.readUnsignedByte()) {
            1 -> Target(null, InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }), input.readUnsignedShort())
            4 -> Target(null, InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }), input.readUnsignedShort())
            3 -> {
                val name = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
                Target(name.lowercase().trimEnd('.'), null, input.readUnsignedShort())
            }
            else -> throw IOException("unknown address type")
        }
    }
}

/** מאיפה הגיע החיבור במקור — נשלח מהמנוע בתוך שם המשתמש של SOCKS5 */
class Origin(val src: InetSocketAddress, val dst: InetSocketAddress) {
    companion object {
        private val LITERAL = Regex("^[0-9A-Fa-f:.]{2,45}$")

        /** "<secret>|<t|u>|<src ip>|<src port>|<dst ip>|<dst port>" */
        fun parse(user: String, secret: String): Origin? {
            val p = user.split('|')
            if (p.size != 6 || p[0] != secret) return null
            if (!LITERAL.matches(p[2]) || !LITERAL.matches(p[4])) return null
            return try {
                Origin(
                    InetSocketAddress(InetAddress.getByName(p[2]), p[3].toInt()),
                    InetSocketAddress(InetAddress.getByName(p[4]), p[5].toInt())
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * שרת SOCKS5 מקומי (רק 127.0.0.1) שמנוע ה-VPN מעביר אליו כל חיבור.
 * כאן מתקבלת ההחלטה: מי האפליקציה, לאן היא מנסה להגיע, והאם מותר.
 * רק מנוע ה-VPN שלנו מכיר את הסוד, כך שאפליקציות אחרות לא יכולות להשתמש בשרת כדי לעקוף.
 */
class LocalProxy(private val policy: ProxyPolicy) {

    companion object {
        const val PROTO_TCP = 6
        const val PROTO_UDP = 17
        private const val CONNECT_TIMEOUT = 8_000
        private const val HANDSHAKE_TIMEOUT = 20_000
        private const val SNI_TIMEOUT = 5_000
        private const val MAX_UDP_REPLY = 8_000
    }

    val secret: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private val server = ServerSocket()
    private val open: MutableSet<Closeable> = ConcurrentHashMap.newKeySet()
    @Volatile private var running = false

    private val pool = ThreadPoolExecutor(0, 2048, 60L, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(null, r, "proxy", 256 * 1024).apply { isDaemon = true }
    }

    val port: Int get() = server.localPort

    fun start() {
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 1024)
        running = true
        Thread({ acceptLoop() }, "proxy-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        closeQuietly(server)
        open.forEach { closeQuietly(it) }
        open.clear()
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (running) {
            val s = try {
                server.accept()
            } catch (_: IOException) {
                if (!running) break
                Thread.sleep(50)
                continue
            }
            track(s)
            try {
                pool.execute { handle(s) }
            } catch (_: RejectedExecutionException) {
                release(s)
            }
        }
    }

    // ---------- לחיצת יד SOCKS5 ----------

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT
            val input = DataInputStream(BufferedInputStream(client.getInputStream(), 16 * 1024))
            val output = client.getOutputStream()

            // 1) שיטות זיהוי — מקבלים רק שם משתמש וסיסמה
            if (input.readUnsignedByte() != 5) return
            val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            if (methods.none { it.toInt() == 2 }) {
                output.write(byteArrayOf(5, 0xFF.toByte()))
                return
            }
            output.write(byteArrayOf(5, 2))

            // 2) שם המשתמש מכיל את הסוד ואת מקור החיבור
            if (input.readUnsignedByte() != 1) return
            val user = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
            input.readFully(ByteArray(input.readUnsignedByte()))
            val origin = Origin.parse(user, secret)
            if (origin == null) {
                output.write(byteArrayOf(1, 1))
                return
            }
            output.write(byteArrayOf(1, 0))

            // 3) הבקשה עצמה
            if (input.readUnsignedByte() != 5) return
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte()
            val target = Target.read(input)
            when (cmd) {
                1 -> handleConnect(client, input, output, origin, target)
                5 -> handleUdp(client, input, output, origin)
                else -> reply(output, 7)
            }
        } catch (_: Exception) {
        } finally {
            release(client)
        }
    }

    private fun reply(out: OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
    }

    private fun blocked(uid: Int, t: Target) {
        if (t.domain != null) policy.blocked(uid, t.domain, true)
        else policy.blocked(uid, t.label, false)
    }

    // ---------- TCP ----------

    private fun handleConnect(client: Socket, input: DataInputStream, output: OutputStream, origin: Origin, target: Target) {
        val uid = policy.ownerUid(PROTO_TCP, origin.src, origin.dst)
        val mode = policy.mode(uid)

        val allowed = when (mode) {
            Mode.BLOCK -> false
            Mode.OPEN -> true
            // דומיין — לפי הרשימה. כתובת IP בלי שם — רק HTTPS, ושם האתר ייבדק בהמשך
            Mode.WHITELIST -> if (target.domain != null) policy.isAllowed(uid, target.domain) else target.port == 443
        }
        if (!allowed) {
            blocked(uid, target)
            reply(output, 2)
            return
        }

        val remote = connect(target)
        if (remote == null) {
            reply(output, 4)
            return
        }
        try {
            reply(output, 0)
            var first: ByteArray? = null
            var seenName: String? = target.domain
            if (mode == Mode.WHITELIST && target.port == 443) {
                // בודקים גם את שם האתר שבתוך HTTPS, לפני שמשהו מגיע לשרת
                val (bytes, host) = readClientHello(client, input)
                if (host != null && !policy.isAllowed(uid, host)) {
                    policy.blocked(uid, host, true)
                    return
                }
                if (host == null && target.domain == null) {
                    blocked(uid, target)
                    return
                }
                first = bytes
                if (seenName == null) seenName = host
            }
            policy.passed(uid, seenName ?: target.label, seenName != null)
            client.soTimeout = 0
            relay(client, input, remote, first)
        } finally {
            release(remote)
        }
    }

    private fun connect(t: Target): Socket? {
        val addresses: List<InetAddress> = try {
            if (t.domain != null) policy.resolve(t.domain).sortedBy { if (it is Inet4Address) 0 else 1 }
            else listOf(t.address ?: return null)
        } catch (_: Exception) {
            return null
        }
        for (a in addresses) {
            if (!running) break
            val s = Socket()
            track(s)
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(a, t.port), CONNECT_TIMEOUT)
                return s
            } catch (_: Exception) {
                release(s)
            }
        }
        return null
    }

    private fun readClientHello(client: Socket, input: InputStream): Pair<ByteArray, String?> {
        val buf = ByteArray(16 * 1024 + 5)
        var len = 0
        client.soTimeout = SNI_TIMEOUT
        try {
            while (len < buf.size) {
                val n = input.read(buf, len, buf.size - len)
                if (n < 0) break
                len += n
                when (val r = Sni.parse(buf, 0, len)) {
                    is Sni.Result.NeedMore -> continue
                    is Sni.Result.None -> return buf.copyOf(len) to null
                    is Sni.Result.Host -> return buf.copyOf(len) to r.name
                }
            }
        } catch (_: SocketTimeoutException) {
        }
        return buf.copyOf(len) to null
    }

    private fun relay(client: Socket, clientIn: InputStream, remote: Socket, first: ByteArray?) {
        val remoteOut = remote.getOutputStream()
        if (first != null && first.isNotEmpty()) remoteOut.write(first)
        val back = try {
            pool.submit { pump(remote.getInputStream(), client.getOutputStream(), remote, client) }
        } catch (_: RejectedExecutionException) {
            return
        }
        val clean = pump(clientIn, remoteOut, client, remote)
        try {
            back.get(if (clean) 60L else 5L, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    /** מעתיק עד הסוף. בסיום רגיל סוגר רק את כיוון הכתיבה בצד השני, בשגיאה סוגר את שניהם. */
    private fun pump(from: InputStream, to: OutputStream, fromSock: Socket, toSock: Socket): Boolean {
        val buf = ByteArray(32 * 1024)
        return try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
            }
            try {
                toSock.shutdownOutput()
            } catch (_: Exception) {
            }
            true
        } catch (_: Exception) {
            closeQuietly(fromSock)
            closeQuietly(toSock)
            false
        }
    }

    // ---------- UDP (מגיע כמסגרות בתוך חיבור TCP) ----------

    private class Frame(val target: Target, val data: ByteArray)

    private fun handleUdp(client: Socket, input: DataInputStream, output: OutputStream, origin: Origin) {
        reply(output, 0)
        client.soTimeout = 0
        val uid = policy.ownerUid(PROTO_UDP, origin.src, origin.dst)
        val mode = policy.mode(uid)
        val first = readFrame(input) ?: return
        val dest = first.target

        val allowed = when {
            // DNS רק דרך המסנן, ו-QUIC חוזר ל-HTTPS רגיל שבו נבדק שם האתר
            dest.port == 53 || dest.port == 443 -> false
            mode == Mode.OPEN -> true
            mode == Mode.BLOCK -> false.also { blocked(uid, dest) }
            dest.domain != null -> policy.isAllowed(uid, dest.domain).also { if (!it) blocked(uid, dest) }
            else -> false.also { blocked(uid, dest) }
        }
        if (!allowed) {
            while (readFrame(input) != null) {
                // מתעלמים עד שהמנוע סוגר את הזרם
            }
            return
        }

        val address = try {
            dest.address ?: policy.resolve(dest.domain ?: return)
                .sortedBy { if (it is Inet4Address) 0 else 1 }.first()
        } catch (_: Exception) {
            return
        }
        policy.passed(uid, dest.label, dest.domain != null)
        val sock = DatagramSocket()
        track(sock)
        try {
            sock.connect(InetSocketAddress(address, dest.port))
            val replyFrom = dest.address ?: address
            try {
                pool.execute {
                    val buf = ByteArray(65535)
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        while (true) {
                            packet.length = buf.size
                            sock.receive(packet)
                            if (packet.length > MAX_UDP_REPLY) continue
                            writeFrame(output, replyFrom, dest.port, buf, packet.length)
                        }
                    } catch (_: Exception) {
                        closeQuietly(sock)
                    }
                }
            } catch (_: RejectedExecutionException) {
                return
            }
            var frame: Frame? = first
            while (frame != null) {
                sock.send(DatagramPacket(frame.data, frame.data.size))
                frame = readFrame(input)
            }
        } catch (_: Exception) {
        } finally {
            release(sock)
        }
    }

    /** מסגרת: אורך נתונים (2), אורך כותרת (1), כתובת SOCKS5, נתונים */
    private fun readFrame(input: DataInputStream): Frame? = try {
        val dataLen = input.readUnsignedShort()
        input.readUnsignedByte()
        val t = Target.read(input)
        val data = ByteArray(dataLen)
        input.readFully(data)
        Frame(t, data)
    } catch (_: IOException) {
        null
    }

    private fun writeFrame(out: OutputStream, from: InetAddress, port: Int, data: ByteArray, len: Int) {
        val a = from.address
        val addrLen = 1 + a.size + 2
        val f = ByteArray(3 + addrLen + len)
        f[0] = (len ushr 8).toByte()
        f[1] = len.toByte()
        f[2] = (3 + addrLen).toByte()
        f[3] = (if (a.size == 4) 1 else 4).toByte()
        System.arraycopy(a, 0, f, 4, a.size)
        f[4 + a.size] = (port ushr 8).toByte()
        f[5 + a.size] = port.toByte()
        System.arraycopy(data, 0, f, 3 + addrLen, len)
        out.write(f)
    }

    // ---------- ניהול סגירה ----------

    private fun track(c: Closeable) {
        open += c
    }

    private fun release(c: Closeable?) {
        if (c == null) return
        open -= c
        closeQuietly(c)
    }

    private fun closeQuietly(c: Closeable?) {
        try {
            c?.close()
        } catch (_: Exception) {
        }
    }
}
