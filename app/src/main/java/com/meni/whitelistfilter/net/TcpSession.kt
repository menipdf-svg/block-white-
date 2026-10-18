package com.meni.whitelistfilter.net

import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.Rules
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import kotlin.random.Random

/**
 * חיבור TCP אחד: מצד אחד האפליקציה (דרך חבילות ב-VPN), מצד שני השרת האמיתי (Socket רגיל).
 * מספרי רצף (seq) נשמרים כ-Int — החשבון עובד נכון גם כשהם מתגלגלים.
 */
class TcpSession(
    private val engine: Engine,
    private val key: String,
    private val uid: Int,
    syn: Packet
) : ChannelHandler {

    private companion object {
        const val CONNECTING = 0
        const val ESTABLISHED = 1
        const val CLOSED = 2

        const val MAX_TO_APP = 256 * 1024
        const val MAX_TO_REMOTE = 64 * 1024
    }

    private val appIp = syn.src
    private val appPort = syn.srcPort
    private val remoteIp = syn.dst
    private val remotePort = syn.dstPort
    private val mss = (if (syn.mss > 0) syn.mss else 536).coerceIn(536, 1400)

    private var channel: SocketChannel? = null
    private var selKey: SelectionKey? = null
    private var state = CONNECTING

    // מה שאנחנו שולחים לאפליקציה
    private val iss = Random.nextInt()
    private var sndUna = iss
    private var sndNxt = iss
    private var appWindow = syn.window
    private val toApp = ByteQueue()
    private var sentOff = 0
    private var finSent = false
    private var finAcked = false

    // מה שהאפליקציה שולחת אלינו
    private var rcvNxt = syn.seq + 1
    private val toRemote = ByteQueue()
    private var appFin = false
    private var remoteFin = false
    private var remoteShutdown = false
    private var lastWindow = 65535

    // בדיקת שם אתר בחיבורי HTTPS
    private var inspected = remotePort != 443

    private val created = System.currentTimeMillis()
    private var lastActivity = created
    private var lastSend = 0L
    private var retransmits = 0

    fun open() {
        try {
            val ch = SocketChannel.open()
            engine.vpn.protect(ch.socket())
            ch.configureBlocking(false)
            channel = ch
            val connected = ch.connect(InetSocketAddress(Packets.toInet(remoteIp), remotePort))
            selKey = ch.register(engine.selector, if (connected) 0 else SelectionKey.OP_CONNECT, this)
            if (connected) onConnected()
        } catch (_: Exception) {
            reset()
        }
    }

    private fun onConnected() {
        state = ESTABLISHED
        sendSynAck()
        sndNxt = iss + 1
        sndUna = iss + 1
        updateInterest()
    }

    private fun sendSynAck() {
        engine.writeTun(
            Packets.tcp(
                remoteIp, appIp, remotePort, appPort, iss, rcvNxt,
                Packets.SYN or Packets.ACK, ourWindow(), mss = 1400
            )
        )
    }

    private fun ourWindow(): Int = (MAX_TO_REMOTE - toRemote.size).coerceIn(0, 65535)

    private fun sendAck() {
        lastWindow = ourWindow()
        engine.writeTun(
            Packets.tcp(remoteIp, appIp, remotePort, appPort, sndNxt, rcvNxt, Packets.ACK, lastWindow)
        )
    }

    // ---------- אירועים מצד השרת ----------

    override fun onKey(key: SelectionKey) {
        if (state == CLOSED) return
        if (key.isValid && key.isConnectable) {
            try {
                if (channel?.finishConnect() == true) onConnected()
            } catch (_: IOException) {
                reset()
                return
            }
        }
        if (key.isValid && key.isReadable) readRemote()
        if (state != CLOSED && key.isValid && key.isWritable) flushToRemote()
    }

    private fun readRemote() {
        val ch = channel ?: return
        val space = MAX_TO_APP - toApp.size
        if (space <= 0) {
            updateInterest()
            return
        }
        val buf = engine.readBuf
        buf.clear()
        buf.limit(minOf(space, buf.capacity()))
        val n = try {
            ch.read(buf)
        } catch (_: IOException) {
            reset()
            return
        }
        lastActivity = engine.now()
        if (n < 0) remoteFin = true
        else if (n > 0) toApp.append(buf.array(), 0, n)
        pushToApp()
        updateInterest()
        checkDone()
    }

    private fun flushToRemote() {
        if (!inspected || state != ESTABLISHED) return
        val ch = channel ?: return
        try {
            while (toRemote.size > 0) {
                if (toRemote.writeTo(ch) == 0) break
            }
            if (toRemote.size == 0 && appFin && !remoteShutdown) {
                remoteShutdown = true
                ch.shutdownOutput()
            }
        } catch (_: IOException) {
            reset()
            return
        }
        // החלון נפתח שוב — מודיעים לאפליקציה שאפשר לשלוח עוד
        if (lastWindow < 16384 && ourWindow() >= 32768) sendAck()
        updateInterest()
        checkDone()
    }

    private fun updateInterest() {
        val k = selKey ?: return
        if (!k.isValid || state != ESTABLISHED) return
        var ops = 0
        if (!remoteFin && toApp.size < MAX_TO_APP) ops = ops or SelectionKey.OP_READ
        if (inspected && toRemote.size > 0) ops = ops or SelectionKey.OP_WRITE
        try {
            k.interestOps(ops)
        } catch (_: Exception) {
        }
    }

    private fun pushToApp() {
        if (state != ESTABLISHED) return
        var sent = false
        while (true) {
            val unsent = toApp.size - sentOff
            if (unsent <= 0) break
            val window = appWindow - (sndNxt - sndUna)
            if (window <= 0) break
            val len = minOf(unsent, mss, window)
            engine.writeTun(
                Packets.tcp(
                    remoteIp, appIp, remotePort, appPort, sndNxt, rcvNxt,
                    Packets.ACK or Packets.PSH, ourWindow(),
                    toApp.bytes, toApp.head + sentOff, len
                )
            )
            sentOff += len
            sndNxt += len
            sent = true
        }
        if (remoteFin && !finSent && sentOff == toApp.size) {
            engine.writeTun(
                Packets.tcp(
                    remoteIp, appIp, remotePort, appPort, sndNxt, rcvNxt,
                    Packets.FIN or Packets.ACK, ourWindow()
                )
            )
            sndNxt += 1
            finSent = true
            sent = true
        }
        if (sent) lastSend = engine.now()
    }

    // ---------- אירועים מצד האפליקציה ----------

    fun onPacket(p: Packet) {
        lastActivity = engine.now()
        if ((p.flags and Packets.RST) != 0) {
            close()
            return
        }
        if (state == CONNECTING) return
        if (state == CLOSED) return

        if ((p.flags and Packets.SYN) != 0) {
            // ה-SYN-ACK שלנו כנראה אבד
            if (sndNxt == iss + 1 && toApp.size == 0) sendSynAck()
            return
        }

        if ((p.flags and Packets.ACK) != 0) onAck(p.ack, p.window)

        val data = p.payload
        val fin = (p.flags and Packets.FIN) != 0
        if (data.isNotEmpty() || fin) {
            val diff = p.seq - rcvNxt
            if (diff > 0) {
                // חבילה מהעתיד (פער) — מבקשים שוב את מה שחסר
                sendAck()
                return
            }
            val skip = -diff
            if (skip < data.size && !appFin) {
                val len = data.size - skip
                if (len > ourWindow() + 1400) {
                    sendAck()
                    return
                }
                toRemote.append(data, skip, len)
                rcvNxt += len
                onAppData()
                if (state == CLOSED) return
            }
            if (fin && !appFin && p.seq + data.size == rcvNxt) {
                appFin = true
                rcvNxt += 1
            }
            sendAck()
        }
        flushToRemote()
        if (state == CLOSED) return
        pushToApp()
        updateInterest()
        checkDone()
    }

    private fun onAck(ack: Int, window: Int) {
        appWindow = window
        val acked = ack - sndUna
        val inflight = sndNxt - sndUna
        if (acked <= 0 || acked > inflight) return
        val dataAcked = minOf(acked, sentOff)
        toApp.drop(dataAcked)
        sentOff -= dataAcked
        sndUna = ack
        if (finSent && ack == sndNxt) finAcked = true
        retransmits = 0
        lastSend = engine.now()
    }

    /** בחיבור HTTPS: מחכים לשם האתר לפני שמעבירים משהו לשרת */
    private fun onAppData() {
        if (inspected) return
        when (val r = Sni.parse(toRemote.bytes, toRemote.head, toRemote.size)) {
            is Sni.Result.NeedMore -> if (toRemote.size > 16384) inspected = true
            is Sni.Result.None -> inspected = true
            is Sni.Result.Host -> {
                if (Rules.isAllowed(uid, r.name)) {
                    inspected = true
                } else {
                    FilterState.block(uid, r.name, true)
                    reset()
                }
            }
        }
    }

    // ---------- זמנים, סגירה ----------

    fun tick(t: Long) {
        if (state == CONNECTING) {
            if (t - created > 30_000) reset()
            return
        }
        if (state != ESTABLISHED) return

        // אם לא קיבלנו אישור — שולחים שוב
        val outstanding = sndNxt != sndUna
        if (outstanding && t - lastSend > 1000) {
            retransmits++
            if (retransmits > 8) {
                reset()
                return
            }
            sentOff = 0
            sndNxt = sndUna
            if (finSent && !finAcked) finSent = false
            pushToApp()
            lastSend = t
        }

        val idleLimit = if (appFin || remoteFin) 60_000L else 3_600_000L
        if (t - lastActivity > idleLimit) reset()
    }

    private fun checkDone() {
        if (appFin && remoteFin && finSent && finAcked && toRemote.size == 0) close()
    }

    private fun reset() {
        if (state == CLOSED) return
        engine.writeTun(
            Packets.tcp(
                remoteIp, appIp, remotePort, appPort, sndNxt, rcvNxt,
                Packets.RST or Packets.ACK, 0
            )
        )
        close()
    }

    fun close() {
        if (state == CLOSED) return
        state = CLOSED
        try {
            selKey?.cancel()
        } catch (_: Exception) {
        }
        try {
            channel?.close()
        } catch (_: Exception) {
        }
        engine.tcp.remove(key)
    }
}
