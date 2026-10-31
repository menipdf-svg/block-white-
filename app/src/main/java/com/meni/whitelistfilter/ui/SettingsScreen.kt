@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meni.whitelistfilter.ChromeLock
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.Lock
import com.meni.whitelistfilter.Protection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** כל הדפים שנפתחים מתפריט ההגדרות */
enum class SettingsPage(val title: String) {
    HUB("הגדרות"),
    PROTECTION("הגנה מפני עקיפה"),
    PIN("קוד נעילה"),
    FRP("הגנת איפוס יצרן"),
    INSTALLS("חסימת התקנות"),
    NETWORK("VPN ו-DNS"),
    CHROME("כרום"),
    IMAGES("סינון תמונות")
}

@Composable
fun SettingsRoot(page: SettingsPage, go: (SettingsPage) -> Unit, onStart: () -> Unit) {
    when (page) {
        SettingsPage.HUB -> SettingsHub(go)
        SettingsPage.PROTECTION -> ProtectionScreen(onStart)
        SettingsPage.PIN -> PinScreen()
        SettingsPage.FRP -> FrpScreen()
        SettingsPage.INSTALLS -> InstallsScreen()
        SettingsPage.NETWORK -> NetworkScreen()
        SettingsPage.CHROME -> ChromeScreen()
        SettingsPage.IMAGES -> ImagesScreen()
    }
}

// ================= התפריט הראשי =================

@Composable
private fun SettingsHub(go: (SettingsPage) -> Unit) {
    PageList {
        item { SectionTitle("הגנה") }
        item {
            NavRow("🛡️", "הגנה מפני עקיפה", "Device Owner, אפשרויות מפתח ונעילות מערכת") {
                go(SettingsPage.PROTECTION)
            }
        }
        item { NavRow("🔒", "קוד נעילה", "קוד שנדרש כדי לפתוח את האפליקציה") { go(SettingsPage.PIN) } }
        item {
            NavRow("🔑", "הגנת איפוס יצרן", "אחרי איפוס רק חשבון גוגל מסוים יפתח את המכשיר") {
                go(SettingsPage.FRP)
            }
        }

        item { SectionTitle("אפליקציות והתקנות") }
        item {
            NavRow("📦", "חסימת התקנות", "חסימה לפי קטגוריות ב-Google Play, ודפדפנים") {
                go(SettingsPage.INSTALLS)
            }
        }

        item { SectionTitle("אינטרנט") }
        item {
            NavRow("🌐", "VPN ו-DNS", "חסימת VPN של אפליקציות אחרות וכפיית שרת DNS") {
                go(SettingsPage.NETWORK)
            }
        }
        item {
            NavRow("🧭", "כרום", "Safe Browsing וחיפוש בטוח מחייבים") { go(SettingsPage.CHROME) }
        }
        item {
            NavRow("🖼️", "סינון תמונות", "טשטוש תמונות בפיירפוקס המסונן") { go(SettingsPage.IMAGES) }
        }
    }
}

// ================= הגנה מפני עקיפה =================

@Composable
fun ProtectionScreen(onStart: () -> Unit) {
    val ctx = LocalContext.current
    val running by FilterState.running.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val owner = remember(refresh) { Protection.isOwner(ctx) }
    val locks = Lock.entries.filter { it != Lock.NO_DEBUGGING }

    PageList {
        if (!owner) {
            item { OwnerSetupCard { refresh++ } }
        } else {
            item { Text("✅ האפליקציה מוגדרת כ-Device Owner", style = MaterialTheme.typography.bodyMedium) }
        }

        item { SectionTitle("אפשרויות מפתח") }
        item {
            GroupCard {
                LockSwitch(Lock.NO_DEBUGGING, owner, refresh, running, onStart) { refresh++ }
                InfoText(
                    "מכבה מיד את ניפוי הבאגים ב-USB ואת אפשרויות המפתח, ולא נותן להפעיל אותם שוב. " +
                        "גם אתה לא תוכל להשתמש ב-ADB כשזה פעיל, אז כדאי להפעיל רק כשסיימת לפתח."
                )
            }
        }

        item { SectionTitle("נעילות מערכת") }
        item {
            GroupCard {
                locks.forEachIndexed { i, lock ->
                    if (i > 0) HorizontalDivider()
                    LockSwitch(lock, owner, refresh, running, onStart) { refresh++ }
                }
            }
        }

        if (owner) {
            item { SectionTitle("ביטול ההגנה") }
            item { RemoveOwnerCard { refresh++ } }
        }
    }
}

@Composable
private fun LockSwitch(
    lock: Lock,
    owner: Boolean,
    refresh: Int,
    running: Boolean,
    onStart: () -> Unit,
    onChanged: () -> Unit
) {
    val ctx = LocalContext.current
    var on by remember(refresh) { mutableStateOf(Protection.isOn(ctx, lock)) }
    SwitchRow(lock.title, lock.desc, on, enabled = owner) {
        if (lock == Lock.ALWAYS_ON_VPN && it && !running) onStart()
        Protection.set(ctx, lock, it)
        on = Protection.isOn(ctx, lock)
        onChanged()
    }
}

// ================= קוד נעילה =================

@Composable
fun PinScreen() {
    PageList { item { PinCard() } }
}

// ================= כרום =================

@Composable
fun ChromeScreen() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val owner = remember(refresh) { Protection.isOwner(ctx) }
    val chromeInstalled = remember { Protection.isChromeInstalled(ctx) }

    PageList {
        if (!owner) item { OwnerNotice() }
        if (!chromeInstalled) {
            item { InfoText("כרום לא מותקן כרגע. ההגדרות יישמרו ויחולו ברגע שהוא יותקן.") }
        }
        item {
            GroupCard {
                ChromeLock.entries.forEachIndexed { i, lock ->
                    if (i > 0) HorizontalDivider()
                    var on by remember(refresh) { mutableStateOf(Protection.isChromeOn(ctx, lock)) }
                    SwitchRow(lock.title, lock.desc, on, enabled = owner) {
                        Protection.setChrome(ctx, lock, it)
                        on = Protection.isChromeOn(ctx, lock)
                        refresh++
                    }
                }
                InfoText("ההגדרות מתעדכנות בכרום תוך כמה שניות. אם כרום פתוח, כדאי לסגור ולפתוח אותו מחדש.")
            }
        }
    }
}

// ================= VPN ו-DNS =================

private data class DnsPreset(val title: String, val host: String)

private val dnsPresets = listOf(
    DnsPreset("Cloudflare", "family.cloudflare-dns.com"),
    DnsPreset("AdGuard", "family.adguard-dns.com"),
    DnsPreset("CleanBrowsing", "family-filter-dns.cleanbrowsing.org"),
    DnsPreset("Google", "dns.google")
)

private val hostRegex =
    Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$")

@Composable
fun NetworkScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val owner = remember(refresh) { Protection.isOwner(ctx) }
    val forcedHost = remember(refresh) { Protection.forcedDnsHost(ctx) }
    var host by rememberSaveable { mutableStateOf(Protection.forcedDnsHost(ctx) ?: "") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    PageList {
        if (!owner) item { OwnerNotice() }

        item { SectionTitle("VPN") }
        item {
            var vpnOn by remember(refresh) { mutableStateOf(Protection.vpnBlocked(ctx)) }
            val count = remember(refresh) { Protection.vpnApps(ctx).size }
            GroupCard {
                SwitchRow(
                    "חסום שימוש ב-VPN",
                    "אי אפשר להגדיר VPN במערכת, ואפליקציות VPN אחרות מוקפאות ולא יכולות לעבוד " +
                        "(גם כאלה שיותקנו בעתיד). הסינון של האפליקציה הזו לא נפגע. כרגע מותקנות: $count",
                    vpnOn,
                    enabled = owner
                ) {
                    Protection.setVpnBlocked(ctx, it)
                    vpnOn = Protection.vpnBlocked(ctx)
                    refresh++
                }
            }
        }

        item { SectionTitle("כפיית DNS") }
        item {
            GroupCard {
                Text(
                    if (forcedHost != null) "✅ DNS נכפה: $forcedHost" else "אין כפיית DNS",
                    style = MaterialTheme.typography.titleMedium
                )
                InfoText(
                    "הכפייה היא ב-DNS פרטי של אנדרואיד, לכן צריך שם שרת (למשל dns.google) ולא כתובת IP. " +
                        "אם השרת לא נגיש, לא יהיה אינטרנט בכלל."
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    dnsPresets.forEach { preset ->
                        AssistChip(onClick = { host = preset.host }, label = { Text(preset.title) })
                    }
                }
                InfoText("Cloudflare, AdGuard ו-CleanBrowsing הם שרתי \"משפחה\" שחוסמים תוכן למבוגרים.")
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim().lowercase() },
                    singleLine = true,
                    label = { Text("שם שרת DNS פרטי") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = owner && !busy,
                        onClick = {
                            if (!hostRegex.matches(host)) {
                                message = "שם השרת לא תקין"
                            } else {
                                busy = true
                                message = "בודק את השרת..."
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { Protection.setForcedDns(ctx, host) }
                                    message =
                                        if (ok) "ה-DNS נכפה"
                                        else "ההגדרה נכשלה. השרת לא נגיש או שאינו תומך ב-DNS פרטי"
                                    busy = false
                                    refresh++
                                }
                            }
                        }
                    ) { Text("כפה DNS") }
                    if (forcedHost != null) {
                        OutlinedButton(
                            enabled = owner && !busy,
                            onClick = {
                                Protection.setForcedDns(ctx, null)
                                message = "הכפייה בוטלה"
                                refresh++
                            }
                        ) { Text("בטל כפייה") }
                    }
                }
                message?.let { InfoText(it) }
            }
        }
    }
}

// ================= הגנת איפוס יצרן =================

@Composable
fun FrpScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val owner = remember(refresh) { Protection.isOwner(ctx) }
    val frpOn = remember(refresh) { Protection.isFrpOn(ctx) }
    var email by rememberSaveable { mutableStateOf(Protection.frpEmail(ctx) ?: "") }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("להפעיל הגנת איפוס יצרן?") },
            text = {
                Text(
                    "אחרי איפוס יצרן רק החשבון $email יפתח את המכשיר. " +
                        "ודא שאתה זוכר את הסיסמה שלו ושהכתובת נכונה. אחרת המכשיר יינעל לצמיתות אחרי איפוס."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    busy = true
                    message = "שולף את מזהה החשבון..."
                    val chosen = email
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try {
                                Protection.grantAccountsPermission(ctx)
                                val id = com.google.android.gms.auth.GoogleAuthUtil.getAccountId(ctx, chosen)
                                if (id.isNullOrBlank()) "ERR:NOID"
                                else if (Protection.setFrp(ctx, id, chosen)) "OK" else "ERR:SET"
                            } catch (_: Exception) {
                                "ERR:EX"
                            }
                        }
                        message = when (result) {
                            "OK" -> "ה-FRP הופעל"
                            "ERR:SET" -> "ההפעלה נכשלה (דורש Device Owner ואנדרואיד 11+)"
                            else -> "לא הצלחתי לקבל מזהה. ודא שהחשבון מחובר עכשיו בטלפון ושיש אינטרנט"
                        }
                        busy = false
                        refresh++
                    }
                }) { Text("הפעל") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("ביטול") } }
        )
    }

    PageList {
        if (!owner) item { OwnerNotice() }
        item {
            GroupCard {
                Text(
                    if (frpOn) "✅ מוגן: אחרי איפוס רק ${Protection.frpEmail(ctx) ?: "החשבון שנבחר"} יפתח את המכשיר"
                    else "אין הגנה",
                    style = MaterialTheme.typography.titleMedium
                )
                InfoText(
                    "אחרי איפוס יצרן (גם דרך Recovery) המכשיר יבקש התחברות עם חשבון גוגל מסוים בלבד. " +
                        "החשבון צריך להיות מחובר עכשיו בטלפון. אם תשכח את הסיסמה שלו, לא תוכל לפתוח את המכשיר אחרי איפוס."
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    singleLine = true,
                    label = { Text("כתובת חשבון גוגל") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = owner && !busy && email.contains("@"), onClick = { confirm = true }) {
                        Text("הפעל FRP")
                    }
                    if (frpOn) {
                        OutlinedButton(
                            enabled = owner && !busy,
                            onClick = {
                                Protection.setFrp(ctx, null, null)
                                message = "ה-FRP בוטל"
                                refresh++
                            }
                        ) { Text("בטל FRP") }
                    }
                }
                if (android.os.Build.VERSION.SDK_INT < 30) InfoText("דורש אנדרואיד 11 ומעלה")
                message?.let { InfoText(it) }
            }
        }
    }
}
