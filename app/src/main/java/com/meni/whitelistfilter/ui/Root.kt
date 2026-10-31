@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meni.whitelistfilter.Pin
import com.meni.whitelistfilter.Rules

/** המסך המוצג כרגע. Crossfade מתחלף לפי הערך הזה, אז כל מסך הוא מפתח נפרד */
private sealed interface Dest {
    data class Tab(val index: Int) : Dest
    data class App(val pkg: String) : Dest
    data class Settings(val page: SettingsPage) : Dest
}

private data class TabSpec(val icon: ImageVector, val label: String)

private val tabSpecs = listOf(
    TabSpec(AppIcons.Shield, "סטטוס"),
    TabSpec(AppIcons.Apps, "אפליקציות"),
    TabSpec(AppIcons.Globe, "דומיינים"),
    TabSpec(AppIcons.Journal, "יומן")
)

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
        else -> tabSpecs[tab].label
    }
    val dest: Dest = when {
        settings != null -> Dest.Settings(settings)
        app != null -> Dest.App(app)
        else -> Dest.Tab(tab)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    if (inSub) {
                        // חץ חזרה מצביע ימינה כי הממשק בעברית (RTL)
                        IconButton(onClick = { goBack() }) {
                            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "חזרה")
                        }
                    }
                },
                actions = {
                    if (settings == null) {
                        IconButton(onClick = {
                            openApp = null
                            settingsName = SettingsPage.HUB.name
                        }) {
                            Icon(Icons.Filled.Settings, contentDescription = "הגדרות")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabSpecs.forEachIndexed { i, spec ->
                    NavigationBarItem(
                        selected = settings == null && tab == i,
                        onClick = {
                            tab = i
                            openApp = null
                            settingsName = null
                        },
                        icon = { Icon(spec.icon, contentDescription = null) },
                        label = { Text(spec.label, maxLines = 1) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Crossfade(targetState = dest, modifier = Modifier.fillMaxSize(), label = "screen") { d ->
                when (d) {
                    is Dest.Settings -> SettingsRoot(d.page, go = { settingsName = it.name }, onStart = onStart)
                    is Dest.App -> AppDetailScreen(d.pkg, onBack = { openApp = null })
                    is Dest.Tab -> when (d.index) {
                        0 -> StatusScreen(onStart, onStop, onOpenSettings = { settingsName = it.name })
                        1 -> AppsScreen(onOpen = { openApp = it })
                        2 -> DomainsScreen()
                        else -> LogScreen()
                    }
                }
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
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(80.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(Modifier.height(Spacing.l))
        Text(
            "הכנס קוד כדי לשנות הגדרות",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.xl))
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
            label = { Text("קוד") },
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
        )
        if (error) {
            Spacer(Modifier.height(Spacing.s))
            StatusMessage("קוד שגוי", Tone.BLOCKED)
        }
        Spacer(Modifier.height(Spacing.l))
        Button(
            onClick = { if (Pin.check(ctx, pin)) onUnlock() else error = true },
            modifier = Modifier.widthIn(min = 200.dp)
        ) { Text("פתח") }
    }
}
