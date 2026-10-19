/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.wlf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Base64
import android.util.Log
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import org.json.JSONObject

/**
 * סינון תמונות לפיירפוקס המסונן.
 *
 * התוסף המובנה (assets/extensions/wlf) תופס כל תמונה, מקטין אותה לדוגמה, ושולח אותה לכאן.
 * מכאן היא נשלחת לשירות באפליקציית המסנן (com.meni.whitelistfilter), שם רצים מודלי ה-AI,
 * והתשובה חוזרת לתוסף.
 */
object WlfFilter {
    private const val TAG = "WlfFilter"
    private const val EXT_ID = "wlf@meni.local"
    private const val EXT_URL = "resource://android/assets/extensions/wlf/"
    private const val PORT = "wlf"

    private const val FILTER_PACKAGE = "com.meni.whitelistfilter"
    private const val FILTER_SERVICE = "com.meni.whitelistfilter.image.ImageCheckService"

    /** בקשה לשירות / תשובה ממנו — חייב להתאים ל-ImageCheckService */
    private const val MSG_CHECK = 1
    private const val MSG_RESULT = 2

    private val controller = BuiltInWebExtensionController(EXT_ID, EXT_URL, PORT)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var app: Context
    private var port: Port? = null
    private var service: Messenger? = null
    private var binding = false

    /** בקשות שמחכות עד שהחיבור לשירות יעלה */
    private val queue = ArrayList<Message>()

    private val replies = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == MSG_RESULT) {
                val data = msg.data
                reply(data.getInt("id"), data.getBoolean("blocked", true))
            }
            true
        },
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = binder?.let { Messenger(it) }
            val pendingNow = ArrayList(queue)
            queue.clear()
            pendingNow.forEach { send(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            binding = false
            try {
                app.unbindService(this)
            } catch (_: Exception) {
            }
        }
    }

    /** נקרא פעם אחת, כשמנוע הדפדפן עולה */
    fun install(context: Context, runtime: WebExtensionRuntime) {
        app = context.applicationContext
        controller.registerBackgroundMessageHandler(
            object : MessageHandler {
                override fun onPortConnected(port: Port) {
                    this@WlfFilter.port = port
                }

                override fun onPortDisconnected(port: Port) {
                    if (this@WlfFilter.port === port) this@WlfFilter.port = null
                }

                override fun onPortMessage(message: Any, port: Port) {
                    val m = message as? JSONObject ?: return
                    if (m.optString("type") == "image") onImage(m)
                }
            },
        )
        controller.install(
            runtime,
            onError = { e -> Log.e(TAG, "Failed to install the filter extension", e) },
        )
        bind()
    }

    private fun bind() {
        if (service != null || binding) return
        binding = true
        val intent = Intent().setComponent(ComponentName(FILTER_PACKAGE, FILTER_SERVICE))
        val ok = try {
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "bind failed: ${e.message}")
            false
        }
        if (!ok) {
            binding = false
            // אפליקציית המסנן לא מותקנת או לא זמינה — חוסמים את מה שמחכה
            val stuck = ArrayList(queue)
            queue.clear()
            stuck.forEach { reply(it.data.getInt("id"), true) }
        }
    }

    private fun onImage(m: JSONObject) {
        val id = m.optInt("id")
        val bytes = try {
            Base64.decode(m.optString("data"), Base64.DEFAULT)
        } catch (_: Exception) {
            reply(id, true)
            return
        }
        val msg = Message.obtain(null, MSG_CHECK).apply {
            data = Bundle().apply {
                putInt("id", id)
                putString("url", m.optString("url"))
                putByteArray("image", bytes)
                putInt("w", m.optInt("w"))
                putInt("h", m.optInt("h"))
            }
            replyTo = replies
        }
        send(msg)
    }

    private fun send(msg: Message) {
        val s = service
        if (s == null) {
            queue += msg
            bind()
            return
        }
        try {
            s.send(msg)
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
            service = null
            queue += msg
            bind()
        }
    }

    private fun reply(id: Int, blocked: Boolean) {
        val json = JSONObject().put("type", "verdict").put("id", id).put("blocked", blocked)
        main.post {
            try {
                port?.postMessage(json)
            } catch (e: Exception) {
                Log.w(TAG, "reply failed: ${e.message}")
            }
        }
    }
}
