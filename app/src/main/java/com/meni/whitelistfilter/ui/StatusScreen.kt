@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.FilterVpnService
import com.meni.whitelistfilter.Lock
import com.meni.whitelistfilter.Pin
import com.meni.whitelistfilter.Protection
import kotlinx.coroutines.delay
import java.util.Locale

private fun megabytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)

@Composable
fun StatusScreen(onStart: () -> Unit, onStop: () -> Unit, onOpenSettings: (SettingsPage) -> Unit) {
    val ctx = LocalContext.current
    val running by FilterState.running.collectAsState()
    val owner = remember(running) { Protection.isOwner(ctx) }
    val alwaysOn = remember(running) { Protection.isOn(ctx, Lock.ALWAYS_ON_VPN) }
    val lockCount = remember(running) { Lock.entries.count { Protection.isOn(ctx, it) } }
    var traffic by remember { mutableStateOf<LongArray?>(null) }
    LaunchedEffect(running) {
        while (running) {
            traffic = FilterVpnService.trafficBytes()
            delay(2000)
        }
        traffic = null
    }

    PageList {
        // ----- מצב הסינון -----
        item {
            GroupCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running) "✅ הסינון פעיל" else "⛔ הסינון כבוי",
                            style = MaterialTheme.typography.titleMedium
                        )
                        InfoText("VPN מקומי — כל התעבורה עוברת רק דרך המכשיר")
                        traffic?.let { InfoText("↑ ${megabytes(it[0])}   ↓ ${megabytes(it[1])}") }
                        if (alwaysOn) InfoText("נעול: עולה אוטומטית")
                    }
                    if (!running) Button(onClick = onStart) { Text("הפעל") }
                    else if (!alwaysOn) OutlinedButton(onClick = onStop) { Text("עצור") }
                }
            }
        }

        // ----- סיכום ההגנה -----
        item {
            GroupCard {
                if (owner) {
                    Text("🛡️ ההגנה פעילה", style = MaterialTheme.typography.titleMedium)
                    InfoText("$lockCount מתוך ${Lock.entries.size} נעילות מערכת פעילות")
                    OutlinedButton(onClick = { onOpenSettings(SettingsPage.PROTECTION) }) { Text("ניהול ההגנה") }
                } else {
                    Text("⚠️ ההגנה לא פעילה", style = MaterialTheme.typography.titleMedium)
                    InfoText(
                        "כדי שלא יהיה אפשר לכבות או להסיר את המסנן, צריך להגדיר אותו כ-Device Owner."
                    )
                    Button(onClick = { onOpenSettings(SettingsPage.PROTECTION) }) { Text("איך מגדירים") }
                }
            }
        }
    }
}

@Composable
internal fun OwnerSetupCard(onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val cmd = Protection.adbCommand(ctx)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("כדי שלא יהיה אפשר לכבות או להסיר את המסנן, צריך להגדיר אותו כ-Device Owner. עושים את זה פעם אחת:")
            Text("1. מסירים זמנית את כל החשבונות מהמכשיר (הגדרות ← חשבונות).")
            Text("2. מפעילים ניפוי באגים ב-USB או אלחוטי (אפשרויות מפתח).")
            Text("3. מריצים מהמחשב את הפקודה:")
            SelectionText(cmd)
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(cmd)) }) { Text("העתק פקודה") }
                TextButton(onClick = onRefresh) { Text("בדוק שוב") }
            }
            Text("4. מחזירים את החשבונות.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SelectionText(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp)
        )
    }
}

@Composable
internal fun PinCard() {
    val ctx = LocalContext.current
    val guard = rememberPinGuard()
    var isSet by remember { mutableStateOf(Pin.isSet(ctx)) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (isSet) "🔒 מוגדר קוד. בלעדיו אי אפשר לפתוח את ההגדרות."
                else "אין קוד. כל מי שפותח את האפליקציה יכול לשנות הגדרות."
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                label = { Text(if (isSet) "קוד חדש" else "קוד (לפחות 4 ספרות)") }
            )
            Row {
                Button(onClick = {
                    if (pin.length >= 4) {
                        val newPin = pin
                        // החלפת קוד קיים דורשת את הקוד הנוכחי
                        guard {
                            Pin.set(ctx, newPin)
                            isSet = true
                            pin = ""
                            message = "הקוד נשמר. אל תשכח אותו!"
                        }
                    } else message = "צריך לפחות 4 ספרות"
                }) { Text(if (isSet) "החלף קוד" else "הגדר קוד") }
                if (isSet) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        guard {
                            Pin.clear(ctx)
                            isSet = false
                            message = "הקוד הוסר"
                        }
                    }) { Text("הסר קוד") }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun RemoveOwnerCard(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val guard = rememberPinGuard()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("מסיר את כל הנעילות ואת הרשאת Device Owner. אחרי זה אפשר יהיה להסיר את האפליקציה.")
            OutlinedButton(onClick = { confirm = true }) { Text("בטל Device Owner") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("לבטל את ההגנה?") },
            text = { Text("כל הנעילות יוסרו. הסינון עצמו ימשיך לעבוד עד שתעצור אותו.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    guard {
                        Protection.removeOwner(ctx)
                        onDone()
                    }
                }) { Text("בטל הגנה") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("השאר") } }
        )
    }
}
