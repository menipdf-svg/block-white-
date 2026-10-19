package com.meni.whitelistfilter.image

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.meni.whitelistfilter.Rules
import java.util.concurrent.Executors

/**
 * שירות בדיקת תמונות לפיירפוקס המסונן.
 * פיירפוקס שולח לכאן דוגמה מוקטנת של כל תמונה, המודלים בודקים, והתשובה חוזרת: לחסום או לא.
 * רק פיירפוקס המסונן (org.mozilla.fenix...) והאפליקציה עצמה רשאים להשתמש בשירות.
 */
class ImageCheckService : Service() {

    companion object {
        private const val TAG = "ImageCheckService"
        const val MSG_CHECK = 1
        const val MSG_RESULT = 2

        /** שתי בדיקות במקביל — המודל הקטן והגדול עובדים בו-זמנית על תמונות שונות */
        private val checks = Executors.newFixedThreadPool(2)
    }

    private lateinit var checker: ImageChecker

    private val messenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == MSG_CHECK) handle(Message.obtain(msg))
            true
        })
    }

    override fun onCreate() {
        super.onCreate()
        Rules.init(this)
        ImageFilterSettings.init(this)
        checker = ImageChecker(this)
        // מחממים את המודלים מראש, כדי שהתמונה הראשונה לא תחכה
        Thread({ ImageJudge.warmUp(applicationContext) }, "model-warmup")
            .apply { priority = Thread.MIN_PRIORITY }
            .start()
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun allowed(uid: Int): Boolean {
        if (uid == android.os.Process.myUid()) return true
        val pkgs = packageManager.getPackagesForUid(uid) ?: return false
        return pkgs.any { it.startsWith("org.mozilla.fenix") }
    }

    private fun handle(msg: Message) {
        val reply = msg.replyTo ?: return
        val data = msg.data
        val id = data.getInt("id")
        if (!allowed(msg.sendingUid)) {
            send(reply, id, true)
            return
        }
        val bytes = data.getByteArray("image")
        val url = data.getString("url") ?: ""
        checks.execute {
            val blocked = try {
                val cfg = ImageFilterSettings.config.value
                when {
                    !cfg.active -> false
                    bytes == null || bytes.isEmpty() -> cfg.blockUnchecked
                    else -> checker.isBlocked(bytes, url)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "check failed: ${e.message}")
                ImageFilterSettings.config.value.blockUnchecked
            }
            send(reply, id, blocked)
        }
    }

    private fun send(reply: Messenger, id: Int, blocked: Boolean) {
        try {
            reply.send(Message.obtain(null, MSG_RESULT).apply {
                this.data = Bundle().apply {
                    putInt("id", id)
                    putBoolean("blocked", blocked)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "reply failed: ${e.message}")
        }
    }
}
