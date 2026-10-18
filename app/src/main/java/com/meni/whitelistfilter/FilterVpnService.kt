package com.meni.whitelistfilter

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.meni.whitelistfilter.net.Engine

class FilterVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.meni.whitelistfilter.START"
        const val ACTION_STOP = "com.meni.whitelistfilter.STOP"
    }

    private var tun: ParcelFileDescriptor? = null
    private var engine: Engine? = null

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

        val pfd = try {
            Builder()
                .setSession("מסנן רשימה לבנה")
                .setMtu(Engine.MTU)
                .addAddress("10.111.222.1", 32)
                .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
                .addRoute("0.0.0.0", 0) // כל התעבורה עוברת דרכנו
                .addRoute("::", 0)
                .addDnsServer("10.111.222.2")
                .addDisallowedApplication(packageName)
                .setBlocking(false)
                .setMetered(false)
                .establish()
        } catch (_: Exception) {
            null
        } ?: return

        tun = pfd
        engine = Engine(this, pfd).also { it.start() }
        FilterState.running.value = true
    }

    @Synchronized
    private fun stopFilter() {
        engine?.stop()
        engine = null
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        FilterState.running.value = false
    }
}
