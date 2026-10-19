package com.meni.whitelistfilter.firefox

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.util.Log
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.Rules
import com.meni.whitelistfilter.browser.AdBlock
import com.meni.whitelistfilter.image.ImageFilterSettings
import com.meni.whitelistfilter.image.WebFilter
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.Executors

/**
 * מנוע ה-Firefox (GeckoView) ותוסף הסינון שלו.
 * התוסף (assets/extensions/wlf) מתחבר לכאן ב-native messaging:
 *  - מקבל מכאן את החוקים (רשימה לבנה, פרסומות, האם סינון תמונות פעיל)
 *  - שולח לכאן כל תמונה לפני שהיא מגיעה לדף, ומקבל בחזרה: להעביר או משטח חסום
 */
object GeckoFilter {
    private const val TAG = "GeckoFilter"
    private const val EXT_URI = "resource://android/assets/extensions/wlf/"
    private const val EXT_ID = "wlf@meni.local"
    private const val NATIVE_APP = "wlf"

    private var runtime: GeckoRuntime? = null
    private var port: WebExtension.Port? = null
    private lateinit var app: Context
    private lateinit var filter: WebFilter
    private val main = Handler(Looper.getMainLooper())
    private val checks = Executors.newFixedThreadPool(2)
    private val scope = MainScope()

    /** כתובת דף הבית של התוסף (moz-extension://...), מגיעה כשהתוסף מתחבר */
    @Volatile
    var homeUrl: String? = null
        private set

    /** חייב לרוץ על התהליכון הראשי. יש רק מנוע אחד לכל האפליקציה */
    fun runtime(ctx: Context): GeckoRuntime {
        runtime?.let { return it }
        app = ctx.applicationContext
        Rules.init(app)
        ImageFilterSettings.init(app)
        filter = WebFilter(app, "Mozilla/5.0")

        val rt = GeckoRuntime.create(app, GeckoRuntimeSettings.Builder().consoleOutput(false).build())
        runtime = rt

        rt.webExtensionController.ensureBuiltIn(EXT_URI, EXT_ID).accept(
            { ext -> ext?.setMessageDelegate(messageDelegate, NATIVE_APP) },
            { e -> Log.e(TAG, "extension install failed", e) }
        )

        // כל שינוי בחוקים או בהגדרות נשלח מיד לתוסף
        scope.launch {
            combine(Rules.data, ImageFilterSettings.config) { _, _ -> Unit }.collect { sendRules() }
        }
        return rt
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(p: WebExtension.Port) {
            port = p
            p.setDelegate(portDelegate)
            sendRules()
        }
    }

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, p: WebExtension.Port) {
            val m = message as? JSONObject ?: return
            when (m.optString("type")) {
                "hello" -> homeUrl = m.optString("home").takeIf { it.isNotBlank() }
                "blocked" -> FilterState.block(Process.myUid(), m.optString("host"), true)
                "image" -> {
                    val id = m.optInt("id")
                    val url = m.optString("url")
                    val data = m.optString("data")
                    checks.execute {
                        val out = try {
                            filter.checkForGecko(Base64.decode(data, Base64.DEFAULT), url)
                        } catch (e: Throwable) {
                            Log.w(TAG, "image check: ${e.message}")
                            null
                        }
                        reply(id, out != null, out)
                    }
                }
                "inline" -> {
                    val id = m.optInt("id")
                    val dataUrl = m.optString("data")
                    checks.execute {
                        val blocked = try {
                            val comma = dataUrl.indexOf(',')
                            val meta = if (comma > 5) dataUrl.substring(5, comma) else ""
                            val body = if (comma >= 0) dataUrl.substring(comma + 1) else ""
                            val bytes = if (meta.contains(";base64")) Base64.decode(body, Base64.DEFAULT)
                            else java.net.URLDecoder.decode(body, "UTF-8").toByteArray()
                            filter.isBlocked(bytes, "inline:" + dataUrl.take(40))
                        } catch (e: Throwable) {
                            ImageFilterSettings.config.value.blockUnchecked
                        }
                        reply(id, blocked, null)
                    }
                }
            }
        }

        override fun onDisconnect(p: WebExtension.Port) {
            if (port === p) port = null
        }
    }

    private fun reply(id: Int, blocked: Boolean, data: ByteArray?) {
        val msg = JSONObject()
            .put("type", "verdict")
            .put("id", id)
            .put("blocked", blocked)
            .put("data", data?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL)
        main.post { port?.postMessage(msg) }
    }

    private fun sendRules() {
        if (!::app.isInitialized) return
        val cfg = ImageFilterSettings.config.value
        val rule = Rules.ruleForPackage(app.packageName)
        val domains = Rules.data.value.global + rule.domains
        val msg = JSONObject()
            .put("type", "rules")
            .put("mode", rule.mode.name)
            .put("domains", JSONArray(domains.toList()))
            .put("ads", JSONArray(AdBlock.all().toList()))
            .put("blockAds", cfg.blockAds)
            .put("imageActive", cfg.active)
        main.post { port?.postMessage(msg) }
    }
}
