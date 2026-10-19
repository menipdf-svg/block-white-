package com.meni.whitelistfilter.firefox

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
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
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.net.URLEncoder

/**
 * דפדפן ניסיוני על מנוע Firefox (GeckoView).
 * הסינון נעשה ע"י התוסף המובנה — ראו GeckoFilter.
 */
class FirefoxActivity : ComponentActivity() {

    private lateinit var session: GeckoSession
    private lateinit var geckoView: GeckoView

    private var address by mutableStateOf("")
    private var loadProgress by mutableIntStateOf(100)
    private var canGoBack by mutableStateOf(false)
    private var canGoForward by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val runtime = GeckoFilter.runtime(this)
        session = GeckoSession()
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                loadProgress = progress
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                address = displayUrl(url)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@FirefoxActivity.canGoBack = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                this@FirefoxActivity.canGoForward = canGoForward
            }
        }
        session.open(runtime)
        geckoView = GeckoView(this).apply { setSession(session) }

        val start = intent?.dataString?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (start != null) load(start) else goHome()

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    /** דפי התוסף (דף הבית, דף חסימה) מוצגים עם כתובת ריקה */
    private fun displayUrl(url: String?): String =
        if (url == null || url.startsWith("moz-extension:") || url == "about:blank") "" else url

    private fun toUrl(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (!t.contains(' ') && t.contains('.')) return "https://$t"
        return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }

    private fun load(url: String) {
        address = url
        session.loadUri(url)
    }

    /** דף הבית של התוסף. הכתובת שלו מגיעה כשהתוסף עולה — מחכים לה עד 5 שניות */
    private fun goHome(tries: Int = 0) {
        val home = GeckoFilter.homeUrl
        when {
            home != null -> {
                session.loadUri(home)
                address = ""
            }
            tries < 25 -> Handler(Looper.getMainLooper()).postDelayed({ goHome(tries + 1) }, 200)
            else -> session.loadUri("about:blank")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val focus = LocalFocusManager.current
        var editing by remember { mutableStateOf<String?>(null) }

        BackHandler(enabled = canGoBack) { session.goBack() }

        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🦊", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 6.dp))
                    OutlinedTextField(
                        value = editing ?: address,
                        onValueChange = { editing = it },
                        singleLine = true,
                        placeholder = { Text("כתובת או חיפוש") },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f).height(52.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            editing?.takeIf { it.isNotBlank() }?.let { load(toUrl(it)) }
                            editing = null
                            focus.clearFocus()
                        })
                    )
                    IconButton(onClick = { session.goForward() }, enabled = canGoForward) {
                        Icon(Icons.Filled.ArrowForward, "קדימה")
                    }
                    IconButton(onClick = { session.reload() }) { Icon(Icons.Filled.Refresh, "רענן") }
                    IconButton(onClick = { goHome() }) { Icon(Icons.Filled.Home, "דף הבית") }
                }
            }
            if (loadProgress < 100) {
                LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            AndroidView(factory = { geckoView }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { load(it) }
    }

    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }
}
