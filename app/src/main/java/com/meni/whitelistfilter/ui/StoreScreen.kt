package com.meni.whitelistfilter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.meni.whitelistfilter.InstallGuardBridge
import com.meni.whitelistfilter.Protection
import com.meni.whitelistfilter.store.StoreCatalog
import com.meni.whitelistfilter.store.StoreGuard
import com.meni.whitelistfilter.store.StoreInstaller

@Composable
fun StoreScreen() {
    val ctx = LocalContext.current
    val guard = rememberPinGuard()
    val version by StoreCatalog.version.collectAsState()
    val statuses by StoreInstaller.status.collectAsState()
    val owner = remember(version) { Protection.isOwner(ctx) }
    val apps = remember(version) { StoreCatalog.apps(ctx) }
    var only by remember(version) { mutableStateOf(StoreGuard.enabled(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        error = null
        StoreCatalog.refresh(ctx) { e ->
            loading = false
            error = e
        }
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.l, top = Spacing.s, end = Spacing.l, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        if (!owner) item { OwnerNotice() }

        item {
            InsetGroup {
                SwitchRow(
                    title = "רק מהחנות",
                    desc = "כל אפליקציה חדשה שאינה בקטלוג מוסרת מיד. מה שכבר מותקן לא נפגע.",
                    checked = only,
                    enabled = owner
                ) { want ->
                    val apply: () -> Unit = {
                        StoreGuard.setEnabled(ctx, want)
                        only = want
                        if (want) InstallGuardBridge.sweep(ctx)
                    }
                    if (want) apply() else guard(apply)
                }
            }
        }

        item {
            InsetGroup {
                Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    InfoText("קטלוג: ${StoreCatalog.url(ctx)}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { refresh() }, enabled = !loading) {
                            Text(if (loading) "מרענן…" else "רענן קטלוג")
                        }
                    }
                    error?.let { StatusMessage("הרענון נכשל: $it", Tone.BLOCKED) }
                }
            }
        }

        if (apps.isEmpty()) {
            item {
                InfoText("הקטלוג ריק. מוסיפים אפליקציות עם store/add_app.py ודוחפים את catalog.json ל-GitHub.")
            }
        }

        items(apps, key = { it.pkg }) { app ->
            val installed = remember(version, statuses) { StoreInstaller.installedVersion(ctx, app.pkg) }
            val note = statuses[app.pkg]
            val needsUpdate = installed != null && installed < app.versionCode
            ListCard {
                Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(app.name, style = MaterialTheme.typography.titleSmall)
                    if (app.desc.isNotEmpty()) InfoText(app.desc)
                    InfoText(
                        buildString {
                            append(app.pkg)
                            if (app.version.isNotEmpty()) append(" · ").append(app.version)
                        }
                    )
                    when {
                        note != null -> StatusMessage(note, if (note.startsWith("נכשל")) Tone.BLOCKED else Tone.NEUTRAL)
                        installed == null -> {}
                        needsUpdate -> StatusMessage("יש עדכון", Tone.CAUTION)
                        else -> StatusMessage("מותקנת", Tone.ALLOWED)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        if (installed == null || needsUpdate) {
                            Button(
                                onClick = { StoreInstaller.install(ctx, app) },
                                enabled = note == null || note.startsWith("נכשל")
                            ) { Text(if (installed == null) "התקן" else "עדכן") }
                        }
                        if (installed != null) {
                            OutlinedButton(onClick = {
                                guard { InstallGuardBridge.remove(ctx, app.pkg) }
                            }) { Text("הסר") }
                        }
                    }
                }
            }
        }
    }
}
