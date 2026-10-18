package com.meni.whitelistfilter

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object FilterState {
    val running = MutableStateFlow(false)
    val blocked = MutableStateFlow<List<String>>(emptyList())

    fun reportBlocked(domain: String) {
        blocked.update { (listOf(domain) + (it - domain)).take(50) }
    }
}

class FilterVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.meni.whitelistfilter.START"
        const val ACTION_STOP = "com.meni.whitelistfilter.STOP"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VPN_DNS = "10.111.222.2"
        private val UPSTREAM: InetAddress =
            InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
    }

    @Volatile private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var pool: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFilter()
            stopSelf()
            return START_NOT_STICKY
        }
        startFilter()
        return START_STICKY
    }

    override fun onRevoke() {
        stopFilter()
        stopSelf()
    }

    override fun onDestroy() {
        stopFilter()
        super.onDestroy()
    }

    @Synchronized
    private fun startFilter() {
        if (tun != null) return
        Whitelist.init(this)

        val pfd = Builder()
            .setSession("מסנן רשימה לבנה")
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)
            .establish() ?: return

        val executor = Executors.newFixedThreadPool(8)
        tun = pfd
        pool = executor
        worker = Thread({ runLoop(pfd, executor) }, "dns-filter").also { it.start() }
        FilterState.running.value = true
    }

    @Synchronized
    private fun stopFilter() {
        val old = tun
        tun = null
        try {
            old?.close()
        } catch (_: Exception) {
        }
        worker?.interrupt()
        worker = null
        pool?.shutdownNow()
        pool = null
        FilterState.running.value = false
    }

    private fun runLoop(pfd: ParcelFileDescriptor, executor: ExecutorService) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (tun === pfd) {
                val len = input.read(buf)
                if (len <= 0) {
                    Thread.sleep(10)
                    continue
                }
                val query = DnsPacket.parse(buf.copyOf(len)) ?: continue

                if (Whitelist.isAllowed(query.domain)) {
                    try {
                        executor.execute { forward(query, output) }
                    } catch (_: Exception) {
                    }
                } else {
                    FilterState.reportBlocked(query.domain)
                    write(output, query.blockedResponse())
                }
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        }
    }

    private fun forward(query: DnsQuery, output: FileOutputStream) {
        try {
            DatagramSocket().use { socket ->
                protect(socket) // השאילתה לשרת האמיתי לא עוברת דרך ה-VPN
                socket.soTimeout = 5000
                socket.send(DatagramPacket(query.dnsPayload, query.dnsPayload.size, UPSTREAM, 53))
                val resp = ByteArray(4096)
                val packet = DatagramPacket(resp, resp.size)
                socket.receive(packet)
                write(output, query.responsePacket(resp.copyOf(packet.length)))
            }
        } catch (_: Exception) {
        }
    }

    private fun write(output: FileOutputStream, data: ByteArray) {
        synchronized(output) {
            try {
                output.write(data)
            } catch (_: IOException) {
            }
        }
    }
}
