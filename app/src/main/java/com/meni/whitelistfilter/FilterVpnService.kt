package com.meni.whitelistfilter

import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.meni.whitelistfilter.net.AppPolicy
import com.meni.whitelistfilter.net.LocalProxy
import com.meni.whitelistfilter.net.TProxyService
import java.io.File

/**
 * VPN מקומי: כל התעבורה של המכשיר נכנסת לממשק tun.
 * מנוע hev-socks5-tunnel (קוד C) מרכיב ממנה חיבורים ומעביר כל אחד לשרת SOCKS5 שלנו בתוך האפליקציה,
 * ושם מוחלט אם לאפשר אותו. שום דבר לא עובר דרך שרת חיצוני.
 */
class FilterVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.meni.whitelistfilter.START"
        const val ACTION_STOP = "com.meni.whitelistfilter.STOP"

        private const val MTU = 8500
        private const val TUN_IPV4 = "198.18.0.1"
        private const val TUN_IPV6 = "fc00::1"
        private const val DNS_IPV4 = "198.18.0.2"

        /** [שליחה, קבלה] בבתים, או null כשהמסנן כבוי */
        fun trafficBytes(): LongArray? = try {
            if (!FilterState.running.value) null
            else TProxyService.TProxyGetStats().let { longArrayOf(it[1], it[3]) }
        } catch (_: Throwable) {
            null
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var proxy: LocalProxy? = null

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
        Rules.init(this)

        // 1) השרת המקומי שמחליט על כל חיבור
        val p = LocalProxy(AppPolicy(getSystemService(ConnectivityManager::class.java)))
        try {
            p.start()
        } catch (_: Exception) {
            return
        }

        // 2) הגדרות למנוע
        val config = File(filesDir, "tunnel.yml")
        config.writeText(tunnelConfig(p.port, p.secret))

        // 3) ממשק ה-VPN — כל התעבורה, IPv4 ו-IPv6
        val pfd = try {
            Builder()
                .setSession("מסנן רשימה לבנה")
                .setMtu(MTU)
                .addAddress(TUN_IPV4, 32)
                .addAddress(TUN_IPV6, 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(DNS_IPV4)
                .addDisallowedApplication(packageName) // החיבורים שלנו יוצאים ישר לרשת
                .setBlocking(false)
                .setMetered(false)
                .establish()
        } catch (_: Exception) {
            null
        }
        if (pfd == null) {
            p.stop()
            return
        }

        // 4) הפעלת המנוע
        val started = try {
            TProxyService.TProxyStartService(config.absolutePath, pfd.fd)
        } catch (_: Throwable) {
            false
        }
        if (!started) {
            closeQuietly(pfd)
            p.stop()
            return
        }

        tun = pfd
        proxy = p
        FilterState.running.value = true
    }

    @Synchronized
    private fun stopFilter() {
        if (tun != null) {
            try {
                TProxyService.TProxyStopService()
            } catch (_: Throwable) {
            }
        }
        closeQuietly(tun)
        tun = null
        proxy?.stop()
        proxy = null
        FilterState.running.value = false
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor?) {
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * mapdns: המנוע עונה בעצמו לשאילתות DNS עם כתובות "מדומות", וכשאפליקציה מתחברת לכתובת כזו
     * הוא שולח לשרת שלנו את שם הדומיין עצמו. כך ההחלטה תמיד נעשית לפי שם האתר.
     */
    private fun tunnelConfig(port: Int, secret: String) = """
        |tunnel:
        |  mtu: $MTU
        |  ipv4: $TUN_IPV4
        |  ipv6: '$TUN_IPV6'
        |socks5:
        |  port: $port
        |  address: 127.0.0.1
        |  udp: 'tcp'
        |  username: '$secret'
        |  password: 'x'
        |mapdns:
        |  address: $DNS_IPV4
        |  port: 53
        |  network: 198.18.192.0
        |  netmask: 255.255.192.0
        |  cache-size: 10000
        |misc:
        |  connect-timeout: 20000
        |  tcp-read-write-timeout: 300000
        |  udp-read-write-timeout: 60000
        |  log-level: warn
        |""".trimMargin()
}
