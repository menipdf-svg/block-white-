package com.meni.whitelistfilter

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.meni.whitelistfilter.image.ImageFilterSettings
import com.meni.whitelistfilter.image.WebFilter
import java.net.URLEncoder

/** דפדפן מסונן: כל דומיין נבדק מול הרשימה הלבנה, וכל תמונה עוברת דרך המודל המקומי */
class BrowserActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var filter: WebFilter

    private var address by mutableStateOf("")
    private var loadProgress by mutableIntStateOf(100)
    private var canGoBack by mutableStateOf(false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Rules.init(this)
        ImageFilterSettings.init(this)

        web = WebView(this)
        filter = WebFilter(this, WebSettings.getDefaultUserAgent(this))

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                filter.handle(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                // לא פותחים אפליקציות אחרות מתוך הדפדפן (intent://, market://, tel: ...)
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!url.startsWith("data:")) address = url
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                canGoBack = view.canGoBack()
                if (!url.startsWith("data:")) address = url
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadProgress = newProgress
            }
        }

        // גם Service Workers של אתרים עוברים דרך אותו מסנן
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    filter.handle(request)
            })
        } catch (_: Exception) {
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else openIntent(intent)

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    BrowserScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BrowserScreen() {
        val focus = LocalFocusManager.current
        var editing by remember { mutableStateOf<String?>(null) }

        BackHandler(enabled = canGoBack) { web.goBack() }

        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconTextButton("←", enabled = canGoBack) { web.goBack() }
                    OutlinedTextField(
                        value = editing ?: address,
                        onValueChange = { editing = it },
                        singleLine = true,
                        placeholder = { Text("כתובת או חיפוש") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            editing?.let { load(toUrl(it)) }
                            editing = null
                            focus.clearFocus()
                        })
                    )
                    IconTextButton("⟳") { web.reload() }
                    IconTextButton("⌂") { showHome() }
                }
            }
            if (loadProgress < 100) {
                LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }

    @Composable
    private fun IconTextButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
        TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(8.dp)) {
            Text(text, style = MaterialTheme.typography.titleLarge)
        }
    }

    private fun toUrl(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (!t.contains(' ') && t.contains('.')) return "https://$t"
        return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }

    private fun load(url: String) {
        address = url
        web.loadUrl(url)
    }

    private fun openIntent(intent: Intent?) {
        val data = intent?.dataString
        if (data != null && (data.startsWith("http://") || data.startsWith("https://"))) load(data)
        else showHome()
    }

    /** דף הבית: קישורים לאתרים שברשימה הלבנה */
    private fun showHome() {
        val sites = (Rules.data.value.global + Rules.ruleForPackage(packageName).domains).sorted()
        val links = if (sites.isEmpty()) "<p>אין עדיין אתרים ברשימה הלבנה.</p>"
        else sites.joinToString("") { d ->
            val safe = d.replace(Regex("[^a-z0-9.-]"), "")
            "<a href=\"https://$safe\">$safe</a>"
        }
        val html = """
            <!doctype html><html dir="rtl" lang="he"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body{font-family:sans-serif;margin:0;padding:20px;background:#f6f6f6;color:#222}
              h2{margin:8px 0 16px}
              a{display:block;direction:ltr;text-align:right;background:#fff;border-radius:10px;padding:14px;
                margin:8px 0;color:#1E6B52;text-decoration:none;font-size:17px}
              @media (prefers-color-scheme: dark){body{background:#121212;color:#eee}a{background:#1f1f1f;color:#7fd1b0}}
            </style></head><body>
            <h2>🛡️ דפדפן מסונן</h2>
            $links
            </body></html>
        """.trimIndent()
        address = ""
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
