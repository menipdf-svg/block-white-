package com.meni.whitelistfilter

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.ValueCallback
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.meni.whitelistfilter.browser.BrowserStore
import com.meni.whitelistfilter.browser.DownloadStatus
import com.meni.whitelistfilter.browser.Downloads
import com.meni.whitelistfilter.browser.PageEntry
import com.meni.whitelistfilter.image.ImageCache
import com.meni.whitelistfilter.image.ImageFilterSettings
import com.meni.whitelistfilter.image.ImageJudge
import com.meni.whitelistfilter.image.WebFilter
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** כרטיסייה אחת בדפדפן */
class BrowserTab(val id: Long, val web: WebView, val parentId: Long?) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var thumb by mutableStateOf<ImageBitmap?>(null)
}

private enum class Screen { BROWSER, TABS, BOOKMARKS, HISTORY, DOWNLOADS }

/**
 * הגשר בין הדף לאפליקציה: הסקריפט בדף שולח לכאן תמונות מוטמעות (data: / blob:),
 * המודלים בודקים אותן ברקע, והתשובה חוזרת לדף.
 */
class FilterBridge(private val web: WebView, private val filter: WebFilter) {
    @JavascriptInterface
    fun active(): Boolean = ImageFilterSettings.config.value.active

    @JavascriptInterface
    fun check(id: String, dataUrl: String) {
        val safeId = id.filter { it.isLetterOrDigit() }.take(24)
        if (safeId.isEmpty()) return
        checks.execute {
            val blocked = try {
                val bytes = decodeDataUrl(dataUrl)
                if (bytes == null) true else filter.isBlocked(bytes, "inline:" + dataUrl.take(40))
            } catch (_: Throwable) {
                ImageFilterSettings.config.value.blockUnchecked
            }
            web.post {
                try {
                    web.evaluateJavascript("window.__wlfDone&&__wlfDone('" + safeId + "'," + blocked + ")", null)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun decodeDataUrl(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (!url.startsWith("data:") || comma < 0) return null
        val meta = url.substring(5, comma)
        val body = url.substring(comma + 1)
        return if (meta.contains(";base64")) android.util.Base64.decode(body, android.util.Base64.DEFAULT)
        else java.net.URLDecoder.decode(body, "UTF-8").toByteArray()
    }

    companion object {
        /** שתי בדיקות במקביל — המודל הקטן והגדול עובדים בו-זמנית על תמונות שונות */
        private val checks = java.util.concurrent.Executors.newFixedThreadPool(2)
    }
}

/**
 * תמונות שמוטמעות בקוד הדף (data: / blob:) לא עוברות דרך בקשות רשת, ולכן הן נבדקות כך:
 * מסתירים את התמונה (בלי לשנות את הפריסה), שולחים אותה למודלים, ולפי התשובה
 * מציגים אותה או מחליפים במשטח אפור. רץ פעם אחת לכל דף.
 */
private val INLINE_IMAGE_SCRIPT = """
(function(){
  try { if (window.__wlf || !window.WLF || !WLF.active()) return; } catch(e) { return; }
  window.__wlf = 1;
  var G = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="4" height="4"><rect width="4" height="4" fill="#c8c8c8"/></svg>');
  var seq = 0, pending = {};
  function inline(s) {
    if (!s || s === G) return false;
    return (s.indexOf('data:image') === 0 && s.indexOf('data:image/svg') !== 0) || s.indexOf('blob:') === 0;
  }
  function block(img) {
    try {
      if (!img.style.width && img.width) img.style.width = img.width + 'px';
      if (!img.style.height && img.height) img.style.height = img.height + 'px';
      img.removeAttribute('srcset');
      img.src = G;
      img.style.visibility = '';
    } catch (e) {}
  }
  window.__wlfDone = function(id, blocked) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    if (p.bg) {
      // תמונת רקע: מחזירים אותה, או צובעים באפור
      p.el.__wlfBg = p.src;
      if (blocked) { p.el.style.backgroundImage = 'none'; p.el.style.backgroundColor = '#c8c8c8'; }
      else p.el.style.backgroundImage = p.orig;
      return;
    }
    var img = p.img;
    // אם בינתיים התמונה הוחלפה במשהו אחר — הבדיקה הזו כבר לא רלוונטית
    if ((img.currentSrc || img.src) !== p.src) { img.style.visibility = ''; chk(img); return; }
    img.__wlfOk = !blocked ? p.src : null;
    if (blocked) block(img); else img.style.visibility = '';
  };
  // שולח לבדיקה. אם אין תשובה תוך 10 שניות — מתייחסים כחסום, כדי שלא תישאר תמונה מוסתרת לנצח
  function sendData(id, src) {
    setTimeout(function() { if (pending[id]) window.__wlfDone(id, true); }, 10000);
    if (src.indexOf('blob:') === 0) {
      fetch(src).then(function(r) { return r.blob(); }).then(function(b) {
        return new Promise(function(res) { var fr = new FileReader(); fr.onload = function() { res(fr.result); }; fr.readAsDataURL(b); });
      }).then(function(d) { WLF.check(id, d); }).catch(function() { window.__wlfDone(id, true); });
    } else {
      WLF.check(id, src);
    }
  }
  function send(img, src) {
    var id = 'i' + (++seq);
    pending[id] = { img: img, src: src };
    img.style.visibility = 'hidden';
    sendData(id, src);
  }
  function chk(img) {
    try {
      var s = img.currentSrc || img.src || '';
      if (!inline(s) || img.__wlfOk === s) return;
      for (var k in pending) if (pending[k].img === img && pending[k].src === s) return;
      var w = img.naturalWidth || 0, h = img.naturalHeight || 0;
      if (img.complete && w > 0 && h > 0 && (w < 40 || h < 40)) return;
      send(img, s);
    } catch (e) {}
  }
  // תמונת רקע מוטמעת: אייקונים קטנים לא נוגעים בהם; גדולות נשלחות לבדיקה
  function bg(el) {
    try {
      var b = el.style && el.style.backgroundImage;
      if (!b || b === 'none') return;
      var m = b.match(/url\(["']?(data:image\/(?!svg)[^"')]+|blob:[^"')]+)/);
      if (!m || el.__wlfBg === m[1]) return;
      for (var k in pending) if (pending[k].el === el) return;
      var r = el.getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && (r.width < 64 || r.height < 64)) return;
      var id = 'b' + (++seq);
      pending[id] = { bg: true, el: el, src: m[1], orig: b };
      el.style.backgroundImage = 'none';
      sendData(id, m[1]);
    } catch (e) {}
  }
  function scan(root) {
    if (!root || root.nodeType !== 1 && root.nodeType !== 9) return;
    if (root.tagName === 'IMG') chk(root); else bg(root);
    var imgs = root.querySelectorAll ? root.querySelectorAll('img') : [];
    for (var i = 0; i < imgs.length; i++) chk(imgs[i]);
    var styled = root.querySelectorAll ? root.querySelectorAll('[style*="background"]') : [];
    for (var j = 0; j < styled.length; j++) bg(styled[j]);
  }
  scan(document);
  new MutationObserver(function(ms) {
    for (var i = 0; i < ms.length; i++) {
      var m = ms[i];
      if (m.type === 'attributes') { if (m.target.tagName === 'IMG') { if (m.attributeName !== 'style') chk(m.target); } else bg(m.target); }
      else for (var k = 0; k < m.addedNodes.length; k++) scan(m.addedNodes[k]);
    }
  }).observe(document.documentElement || document, {childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'srcset', 'style']});
})();
""".trimIndent()

/**
 * דפדפן מסונן: כל דומיין נבדק מול הרשימה הלבנה, וכל תמונה עוברת דרך המודל המקומי.
 * כרטיסיות, סימניות, היסטוריה, הורדות, מסך מלא לווידאו והעלאת קבצים.
 */
class BrowserActivity : ComponentActivity() {

    private lateinit var filter: WebFilter
    private lateinit var userAgent: String
    private val tabIds = AtomicLong(1)

    private val tabs = mutableStateListOf<BrowserTab>()
    private var current by mutableIntStateOf(0)
    private var screen by mutableStateOf(Screen.BROWSER)

    // מסך מלא (וידאו)
    private var customView by mutableStateOf<View?>(null)
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // לחיצה ארוכה על קישור
    private var linkMenu by mutableStateOf<String?>(null)

    // חיפוש בדף
    private var findOpen by mutableStateOf(false)
    private var findText by mutableStateOf("")
    private var findCount by mutableStateOf("")

    // העלאת קבצים לאתרים
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
            fileCallback = null
        }

    private val currentTab: BrowserTab? get() = tabs.getOrNull(current)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Rules.init(this)
        ImageFilterSettings.init(this)
        BrowserStore.init(this)

        // טוענים את מודלי ה-AI ברקע — אבל רק אחרי כמה שניות, כדי לא להתחרות בטעינת הדף הראשון
        val app = applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            Thread({ ImageJudge.warmUp(app) }, "model-warmup").apply { priority = Thread.MIN_PRIORITY }.start()
        }, 4000)

        userAgent = WebSettings.getDefaultUserAgent(this)
        filter = WebFilter(this, userAgent)

        // גם Service Workers של אתרים עוברים דרך אותו מסנן
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    filter.handle(request)
            })
        } catch (_: Exception) {
        }

        // שחזור הכרטיסיות מהפעם הקודמת
        val (saved, savedCurrent) = BrowserStore.loadTabs()
        saved.forEach { url -> newTab(url.ifBlank { null }, select = false) }
        current = savedCurrent.coerceIn(0, maxOf(0, tabs.size - 1))
        if (!openIntent(intent) && tabs.isEmpty()) newTab(null)

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) { App() }
                }
            }
        }
    }

    // =====================================================================
    //  כרטיסיות
    // =====================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: () -> BrowserTab): WebView {
        val web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false // חלונות קופצים רק אחרי לחיצה
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        // תמונות שמוטמעות בקוד הדף (data: / blob:) לא עוברות דרך הבדיקה —
        // סקריפט בתחילת כל דף מחליף אותן במשטח אפור כשסינון התמונות פעיל
        web.addJavascriptInterface(FilterBridge(web, filter), "WLF")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(web, INLINE_IMAGE_SCRIPT, setOf("*"))
            } catch (_: Exception) {
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(view: WebView, url: String) {
                // גיבוי למכשירים בלי סקריפט בתחילת הדף
                view.evaluateJavascript(INLINE_IMAGE_SCRIPT, null)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                filter.handle(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                // לא פותחים אפליקציות אחרות מתוך הדפדפן (intent://, market://, tel: ...)
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab().url = displayUrl(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                val t = tab()
                t.url = displayUrl(url)
                t.canGoBack = view.canGoBack()
                t.canGoForward = view.canGoForward()
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(INLINE_IMAGE_SCRIPT, null)
                val t = tab()
                t.canGoBack = view.canGoBack()
                t.canGoForward = view.canGoForward()
                BrowserStore.addHistory(url, view.title ?: "")
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab().progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                val t = tab()
                t.title = title ?: ""
                view.url?.let { BrowserStore.updateTitle(it, t.title) }
            }

            // קישור שנפתח בחלון חדש (target=_blank) → כרטיסייה חדשה
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                val child = newTab(url = null, select = true, parent = tab().id, loadHome = false)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = child.web
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                tabs.indexOfFirst { it.web === window }.takeIf { it >= 0 }?.let { closeTab(it) }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customCallback?.onCustomViewHidden()
                customView = view
                customCallback = callback
                setFullscreen(true)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    filePicker.launch(fileChooserParams.createIntent())
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        web.setDownloadListener { url, ua, contentDisposition, mime, _ ->
            Downloads.start(this, url, ua, contentDisposition, mime)
            Toast.makeText(this, "ההורדה התחילה", Toast.LENGTH_SHORT).show()
        }

        web.setFindListener { active, total, done ->
            if (done) findCount = if (total == 0) "0" else "${active + 1}/$total"
        }

        // לחיצה ארוכה על קישור → תפריט (פתח בכרטיסייה חדשה, העתק...)
        web.setOnLongClickListener {
            val hit = web.hitTestResult
            when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    hit.extra?.let { linkMenu = it }
                    hit.extra != null
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val msg = Handler(Looper.getMainLooper()) { m ->
                        m.data.getString("url")?.takeIf { it.isNotBlank() }?.let { linkMenu = it }
                        true
                    }.obtainMessage()
                    web.requestFocusNodeHref(msg)
                    true
                }
                else -> false
            }
        }
        return web
    }

    private fun newTab(url: String?, select: Boolean = true, parent: Long? = null, loadHome: Boolean = true): BrowserTab {
        lateinit var tab: BrowserTab
        val web = createWebView { tab }
        tab = BrowserTab(tabIds.getAndIncrement(), web, parent)
        tabs.add(tab)
        if (select) {
            current = tabs.size - 1
            screen = Screen.BROWSER
        }
        when {
            url != null -> load(tab, url)
            loadHome -> showHome(tab)
        }
        return tab
    }

    private fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val parentIndex = tabs.indexOfFirst { it.id == tab.parentId }
        tabs.removeAt(index)
        (tab.web.parent as? ViewGroup)?.removeView(tab.web)
        tab.web.destroy()
        if (tabs.isEmpty()) {
            newTab(null)
            return
        }
        current = when {
            index == current && parentIndex >= 0 -> if (parentIndex > index) parentIndex - 1 else parentIndex
            index < current -> current - 1
            else -> current.coerceAtMost(tabs.size - 1)
        }
    }

    private fun selectTab(index: Int) {
        current = index
        screen = Screen.BROWSER
    }

    /** תמונה קטנה של הכרטיסייה, למסך הכרטיסיות */
    private fun captureThumb(tab: BrowserTab) {
        val w = tab.web.width
        val h = tab.web.height
        if (w <= 0 || h <= 0) return
        try {
            val scale = 0.35f
            val bmp = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            c.translate(-tab.web.scrollX.toFloat(), -tab.web.scrollY.toFloat())
            tab.web.draw(c)
            tab.thumb = bmp.asImageBitmap()
        } catch (_: Throwable) {
        }
    }

    private fun openTabs() {
        currentTab?.let { captureThumb(it) }
        screen = Screen.TABS
    }

    // =====================================================================
    //  ניווט
    // =====================================================================

    private fun displayUrl(url: String): String =
        if (url.startsWith("data:") || url == "about:blank") "" else url

    private fun toUrl(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (!t.contains(' ') && t.contains('.')) return "https://$t"
        return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }

    private fun load(tab: BrowserTab, url: String) {
        tab.url = url
        tab.web.loadUrl(url)
    }

    /** פותח קישור שהגיע מבחוץ. מחזיר true אם נפתח */
    private fun openIntent(intent: Intent?): Boolean {
        val data = intent?.dataString ?: return false
        if (!data.startsWith("http://") && !data.startsWith("https://")) return false
        newTab(data)
        return true
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** דף הבית: סימניות ואתרים מהרשימה הלבנה */
    private fun showHome(tab: BrowserTab) {
        val marks = BrowserStore.bookmarks.value
        val sites = (Rules.data.value.global + Rules.ruleForPackage(packageName).domains).sorted()

        val markHtml = if (marks.isEmpty()) "" else
            "<h3>⭐ סימניות</h3>" + marks.joinToString("") {
                "<a href=\"${esc(it.url)}\"><b>${esc(it.title)}</b><small>${esc(it.url)}</small></a>"
            }
        val siteHtml = if (sites.isEmpty()) "<p>אין עדיין אתרים ברשימה הלבנה.</p>"
        else "<h3>🌐 אתרים מאושרים</h3>" + sites.joinToString("") { d ->
            val safe = d.replace(Regex("[^a-z0-9.-]"), "")
            "<a href=\"https://$safe\" class=\"site\">$safe</a>"
        }

        val html = """
            <!doctype html><html dir="rtl" lang="he"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>דף הבית</title>
            <style>
              body{font-family:sans-serif;margin:0;padding:16px 20px;background:#f6f6f6;color:#222}
              h2{margin:8px 0 12px} h3{margin:20px 0 6px;font-size:16px}
              a{display:block;background:#fff;border-radius:10px;padding:12px 14px;margin:8px 0;
                color:#1E6B52;text-decoration:none;font-size:16px;overflow:hidden}
              a small{display:block;direction:ltr;text-align:right;color:#888;font-size:12px;
                white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:2px}
              a.site{direction:ltr;text-align:right}
              @media (prefers-color-scheme: dark){body{background:#121212;color:#eee}a{background:#1f1f1f;color:#7fd1b0}}
            </style></head><body>
            <h2>🛡️ דפדפן מסונן</h2>
            $markHtml
            $siteHtml
            </body></html>
        """.trimIndent()
        tab.url = ""
        tab.title = "דף הבית"
        tab.web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    // =====================================================================
    //  מסך מלא
    // =====================================================================

    private fun setFullscreen(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitFullscreen() {
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        setFullscreen(false)
    }

    // =====================================================================
    //  ממשק
    // =====================================================================

    @Composable
    private fun App() {
        val video = customView
        val tab = currentTab

        // הדפדפן יכול להציג תמונות מהזיכרון בלי לבקש אותן שוב (ואז הן לא נבדקות).
        // מנקים את הזיכרון בהפעלה ובכל שינוי בהגדרות הסינון, ומרעננים את הדף.
        val cfg by ImageFilterSettings.config.collectAsState()
        var firstConfig by remember { mutableStateOf(true) }
        LaunchedEffect(cfg) {
            tabs.firstOrNull()?.web?.clearCache(true)
            if (firstConfig) firstConfig = false
            else currentTab?.takeIf { it.url.isNotBlank() }?.web?.reload()
        }

        BackHandler(enabled = true) {
            when {
                video != null -> exitFullscreen()
                linkMenu != null -> linkMenu = null
                screen != Screen.BROWSER -> screen = Screen.BROWSER
                findOpen -> closeFind()
                tab != null && tab.web.canGoBack() -> tab.web.goBack()
                tab != null && tab.parentId != null && tabs.any { it.id == tab.parentId } -> closeTab(current)
                else -> moveTaskToBack(true)
            }
        }

        if (video != null) {
            AndroidView(
                factory = { ctx -> FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) } },
                update = { frame ->
                    if (frame.getChildAt(0) !== video) {
                        frame.removeAllViews()
                        (video.parent as? ViewGroup)?.removeView(video)
                        video.keepScreenOn = true
                        frame.addView(video, FrameLayout.LayoutParams(-1, -1))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            return
        }

        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            when (screen) {
                Screen.BROWSER -> BrowserScreen()
                Screen.TABS -> TabsScreen()
                Screen.BOOKMARKS -> PagesScreen(
                    "⭐ סימניות",
                    BrowserStore.bookmarks.collectAsState().value,
                    onRemove = { BrowserStore.removeBookmark(it.url) },
                    onClear = null
                )
                Screen.HISTORY -> PagesScreen(
                    "🕘 היסטוריה",
                    BrowserStore.history.collectAsState().value,
                    onRemove = { BrowserStore.removeHistory(it.url) },
                    onClear = { BrowserStore.clearHistory() }
                )
                Screen.DOWNLOADS -> DownloadsScreen()
            }
        }

        linkMenu?.let { LinkDialog(it) }
    }

    @Composable
    private fun BrowserScreen() {
        val tab = currentTab ?: return
        Column(Modifier.fillMaxSize()) {
            TopBar(tab)
            if (tab.progress < 100) {
                LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            if (findOpen) FindBar(tab)
            AndroidView(
                factory = { ctx -> FrameLayout(ctx) },
                update = { frame ->
                    val web = currentTab?.web ?: return@AndroidView
                    if (frame.getChildAt(0) !== web) {
                        frame.removeAllViews()
                        (web.parent as? ViewGroup)?.removeView(web)
                        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TopBar(tab: BrowserTab) {
        val focus = LocalFocusManager.current
        var editing by remember(tab.id) { mutableStateOf<String?>(null) }
        var menu by remember { mutableStateOf(false) }
        val bookmarks by BrowserStore.bookmarks.collectAsState()

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = editing ?: tab.url,
                    onValueChange = { editing = it },
                    singleLine = true,
                    placeholder = { Text("כתובת או חיפוש") },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f).height(52.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        editing?.takeIf { it.isNotBlank() }?.let { load(tab, toUrl(it)) }
                        editing = null
                        focus.clearFocus()
                    })
                )

                // מספר הכרטיסיות, כמו בכרום
                Box(
                    Modifier.padding(start = 6.dp).size(40.dp).clickable { openTabs() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.size(24.dp)
                            .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (tabs.size > 99) ":)" else tabs.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "תפריט") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val marked = tab.url.isNotBlank() && bookmarks.any { it.url == tab.url }
                        Row(Modifier.padding(horizontal = 8.dp)) {
                            IconButton(onClick = { menu = false; tab.web.goForward() }, enabled = tab.canGoForward) {
                                Icon(Icons.Filled.ArrowForward, "קדימה")
                            }
                            IconButton(
                                onClick = { menu = false; BrowserStore.toggleBookmark(tab.url, tab.title) },
                                enabled = tab.url.isNotBlank()
                            ) {
                                Icon(
                                    Icons.Filled.Star, "סימנייה",
                                    tint = if (marked) Color(0xFFF2B600) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { menu = false; tab.web.reload() }) {
                                Icon(Icons.Filled.Refresh, "רענן")
                            }
                        }
                        HorizontalDivider()
                        MenuItem("➕  כרטיסייה חדשה") { menu = false; newTab(null) }
                        MenuItem("🏠  דף הבית") { menu = false; showHome(tab) }
                        MenuItem("⭐  סימניות") { menu = false; screen = Screen.BOOKMARKS }
                        MenuItem("🕘  היסטוריה") { menu = false; screen = Screen.HISTORY }
                        MenuItem("⬇️  הורדות") { menu = false; screen = Screen.DOWNLOADS }
                        MenuItem("🔍  חיפוש בדף") { menu = false; findOpen = true }
                        MenuItem("🗑️  מחק תמונות מהמטמון") {
                            menu = false
                            ImageCache.clear(this@BrowserActivity)
                            if (tab.url.isNotBlank()) tab.web.reload()
                            Toast.makeText(this@BrowserActivity, "התמונות נמחקו מהמטמון", Toast.LENGTH_SHORT).show()
                        }
                        if (tab.url.isNotBlank()) {
                            MenuItem("📤  שיתוף") {
                                menu = false
                                startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url),
                                        null
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MenuItem(text: String, onClick: () -> Unit) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            DropdownMenuItem(text = { Text(text) }, onClick = onClick)
        }
    }

    // ----- חיפוש בדף -----

    private fun closeFind() {
        findOpen = false
        findText = ""
        findCount = ""
        currentTab?.web?.clearMatches()
    }

    @Composable
    private fun FindBar(tab: BrowserTab) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = findText,
                onValueChange = {
                    findText = it
                    if (it.isBlank()) {
                        tab.web.clearMatches()
                        findCount = ""
                    } else tab.web.findAllAsync(it)
                },
                singleLine = true,
                placeholder = { Text("חיפוש בדף") },
                modifier = Modifier.weight(1f)
            )
            Text(findCount, Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = { tab.web.findNext(false) }) { Icon(Icons.Filled.KeyboardArrowUp, "הקודם") }
            IconButton(onClick = { tab.web.findNext(true) }) { Icon(Icons.Filled.KeyboardArrowDown, "הבא") }
            IconButton(onClick = { closeFind() }) { Icon(Icons.Filled.Close, "סגור") }
        }
    }

    // ----- לחיצה ארוכה על קישור -----

    @Composable
    private fun LinkDialog(url: String) {
        AlertDialog(
            onDismissRequest = { linkMenu = null },
            title = {
                Text(url, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            },
            text = {
                Column {
                    TextButton(onClick = {
                        linkMenu = null
                        newTab(url, select = true, parent = currentTab?.id)
                    }) { Text("פתח בכרטיסייה חדשה") }
                    TextButton(onClick = {
                        linkMenu = null
                        newTab(url, select = false, parent = currentTab?.id)
                        Toast.makeText(this@BrowserActivity, "נפתח בכרטיסייה ברקע", Toast.LENGTH_SHORT).show()
                    }) { Text("פתח ברקע") }
                    TextButton(onClick = {
                        linkMenu = null
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("link", url))
                    }) { Text("העתק קישור") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { linkMenu = null }) { Text("ביטול") } }
        )
    }

    // ----- מסך הכרטיסיות -----

    @Composable
    private fun TabsScreen() {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("כרטיסיות (${tabs.size})") {
                TextButton(onClick = { newTab(null) }) { Text("➕ חדשה") }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(tabs, key = { _, t -> t.id }) { i, t ->
                    val selected = i == current
                    Card(
                        Modifier.fillMaxWidth().height(220.dp).clickable { selectTab(i) }
                            .then(
                                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                else Modifier
                            ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                t.title.ifBlank { t.url.ifBlank { "דף הבית" } },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { closeTab(i) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Close, "סגור", Modifier.size(18.dp))
                            }
                        }
                        val thumb = t.thumb
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (thumb != null) {
                                Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                            } else {
                                Text(
                                    Uri.parse(t.url).host ?: "🛡️",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ----- סימניות / היסטוריה -----

    @Composable
    private fun PagesScreen(title: String, list: List<PageEntry>, onRemove: (PageEntry) -> Unit, onClear: (() -> Unit)?) {
        val fmt = remember { SimpleDateFormat("d/M HH:mm", Locale.getDefault()) }
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title) {
                if (onClear != null && list.isNotEmpty()) TextButton(onClick = onClear) { Text("נקה הכל") }
            }
            if (list.isEmpty()) {
                Text("אין עדיין כלום כאן", Modifier.padding(24.dp))
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                items(list, key = { it.url }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            currentTab?.let { load(it, e.url) }
                            screen = Screen.BROWSER
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                e.url, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(fmt.format(Date(e.time)), style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { onRemove(e) }) { Icon(Icons.Filled.Delete, "מחק") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    // ----- הורדות -----

    @Composable
    private fun DownloadsScreen() {
        val list by Downloads.items.collectAsState()
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("⬇️ הורדות") {}
            if (list.isEmpty()) {
                Text("אין הורדות. הקבצים נשמרים בתיקיית ההורדות של הטלפון.", Modifier.padding(24.dp))
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                items(list, key = { it.id }) { d ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = d.uri != null) {
                            try {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(d.uri, d.mime)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                )
                            } catch (_: Exception) {
                                Toast.makeText(this@BrowserActivity, "אין אפליקציה שפותחת את הקובץ הזה", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                val size = if (d.total > 0) "${mb(d.done)} / ${mb(d.total)}" else mb(d.done)
                                Text("${d.status.title} · $size", style = MaterialTheme.typography.bodySmall)
                                d.note?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                                if (d.status == DownloadStatus.RUNNING) {
                                    if (d.total > 0) {
                                        LinearProgressIndicator(
                                            progress = { (d.done.toFloat() / d.total).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                        )
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                    }
                                }
                            }
                            if (d.status != DownloadStatus.RUNNING) {
                                IconButton(onClick = { Downloads.remove(d.id) }) { Icon(Icons.Filled.Close, "הסר מהרשימה") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)

    @Composable
    private fun ScreenHeader(title: String, actions: @Composable () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { screen = Screen.BROWSER }) {
                // ברשימה מימין לשמאל החץ "אחורה" מצביע ימינה
                Icon(Icons.Filled.ArrowForward, "חזרה")
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            actions()
        }
    }

    // =====================================================================
    //  מחזור חיים
    // =====================================================================

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        BrowserStore.saveTabs(tabs.map { it.url }, current)
        tabs.forEach { it.web.onPause() }
    }

    override fun onResume() {
        super.onResume()
        tabs.forEach { it.web.onResume() }
    }

    override fun onDestroy() {
        tabs.forEach { it.web.destroy() }
        super.onDestroy()
    }
}
