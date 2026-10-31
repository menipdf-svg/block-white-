@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meni.whitelistfilter.Pin
import com.meni.whitelistfilter.Rules

@Composable
fun Root(onStart: () -> Unit, onStop: () -> Unit) {
    val ctx = LocalContext.current
    var unlocked by rememberSaveable { mutableStateOf(!Pin.isSet(ctx)) }
    if (!unlocked) {
        PinLock { unlocked = true }
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openApp by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsName by rememberSaveable { mutableStateOf<String?>(null) }
    val settings = settingsName?.let { n -> SettingsPage.entries.firstOrNull { it.name == n } }
    val app = openApp
    val tabs = listOf("🛡️" to "סטטוס", "📱" to "אפליקציות", "🌐" to "דומיינים", "📋" to "יומן")

    val inSub = app != null || settings != null
    val goBack = {
        when {
            app != null -> openApp = null
            settings != null && settings != SettingsPage.HUB -> settingsName = SettingsPage.HUB.name
            else -> settingsName = null
        }
    }
    BackHandler(enabled = inSub) { goBack() }

    val title = when {
        settings != null -> settings.title
        app != null -> Rules.labelForPackage(app)
        else -> tabs[tab].second
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (inSub) IconButton(onClick = { goBack() }) { Text("→", style = MaterialTheme.typography.titleLarge) }
                },
                actions = {
                    if (settings == null) {
                        IconButton(onClick = {
                            openApp = null
                            settingsName = SettingsPage.HUB.name
                        }) { Text("⚙️", style = MaterialTheme.typography.titleLarge) }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = settings == null && tab == i,
                        onClick = {
                            tab = i
                            openApp = null
                            settingsName = null
                        },
                        icon = { Text(icon) },
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().imePadding()) {
            when {
                settings != null -> SettingsRoot(settings, go = { settingsName = it.name }, onStart = onStart)
                app != null -> AppDetailScreen(app, onBack = { openApp = null })
                tab == 0 -> StatusScreen(onStart, onStop, onOpenSettings = { settingsName = it.name })
                tab == 1 -> AppsScreen(onOpen = { openApp = it })
                tab == 2 -> DomainsScreen()
                else -> LogScreen()
            }
        }
    }
}

@Composable
fun PinLock(onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🔒", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text("הכנס קוד כדי לשנות הגדרות", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = {
                pin = it.filter(Char::isDigit).take(12)
                error = false
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = error,
            label = { Text("קוד") }
        )
        if (error) Text("קוד שגוי", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { if (Pin.check(ctx, pin)) onUnlock() else error = true }) { Text("פתח") }
    }
}
