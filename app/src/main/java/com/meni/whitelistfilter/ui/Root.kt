@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

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
    val tabs = listOf("🛡️" to "סטטוס", "📱" to "אפליקציות", "🌐" to "דומיינים", "🖼️" to "תמונות", "📋" to "יומן")

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (icon, title) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = {
                            tab = i
                            openApp = null
                        },
                        icon = { Text(icon) },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().imePadding()) {
            val app = openApp
            when {
                app != null -> AppDetailScreen(app, onBack = { openApp = null })
                tab == 0 -> StatusScreen(onStart, onStop)
                tab == 1 -> AppsScreen(onOpen = { openApp = it })
                tab == 2 -> DomainsScreen()
                tab == 3 -> ImagesScreen(onBrowserDomains = { openApp = ctx.packageName })
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
