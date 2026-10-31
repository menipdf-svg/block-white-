@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
        item {
            StatusHero(running, alwaysOn, traffic, onStart, onStop)
        }
        item {
            ProtectionSummary(owner, lockCount, onOpenSettings)
        }
    }
}

/** הכרטיס הראשי: מצב הסינון בגודל גדול, עם תעבורה והפעולה הבאה */
@Composable
private fun StatusHero(
    running: Boolean,
    alwaysOn: Boolean,
    traffic: LongArray?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val tone = if (running) Tone.ALLOWED else Tone.NEUTRAL
    InsetGroup {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = tone.container(),
                    contentColor = tone.onContainer(),
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(AppIcons.Shield, contentDescription = null, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) "הסינון פעיל" else "הסינון כבוי",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    InfoText("VPN מקומי — כל התעבורה עוברת רק דרך המכשיר")
                }
            }

            traffic?.let { t ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                    TrafficStat("שליחה", megabytes(t[0]))
                    TrafficStat("קבלה", megabytes(t[1]))
                }
            }
            if (alwaysOn) StatusMessage("נעול: עולה אוטומטית", Tone.NEUTRAL)

            if (!running) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("הפעל") }
            } else if (!alwaysOn) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("עצור") }
            }
        }
    }
}

@Composable
private fun TrafficStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/** סיכום ההגנה: כמה נעילות פעילות מתוך כולן, עם מד התקדמות */
@Composable
private fun ProtectionSummary(owner: Boolean, lockCount: Int, onOpenSettings: (SettingsPage) -> Unit) {
    GroupCard {
        if (owner) {
            Text("ההגנה פעילה", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = lockCount / Lock.entries.size.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
            InfoText("$lockCount מתוך ${Lock.entries.size} נעילות מערכת פעילות")
            OutlinedButton(onClick = { onOpenSettings(SettingsPage.PROTECTION) }) { Text("ניהול ההגנה") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Tone.CAUTION.solid(),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(Spacing.xs))
                Text("ההגנה לא פעילה", style = MaterialTheme.typography.titleMedium)
            }
            InfoText("כדי שלא יהיה אפשר לכבות או להסיר את המסנן, צריך להגדיר אותו כ-Device Owner.")
            Button(onClick = { onOpenSettings(SettingsPage.PROTECTION) }) { Text("איך מגדירים") }
        }
    }
}

@Composable
internal fun OwnerSetupCard(onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val cmd = Protection.adbCommand(ctx)
    GroupCard {
        Text("הגדרה חד-פעמית", style = MaterialTheme.typography.titleSmall)
        Text("כדי שלא יהיה אפשר לכבות או להסיר את המסנן, צריך להגדיר אותו כ-Device Owner. עושים את זה פעם אחת:")
        Text("1. מסירים זמנית את כל החשבונות מהמכשיר (הגדרות ← חשבונות).", style = MaterialTheme.typography.bodyMedium)
        Text("2. מפעילים ניפוי באגים ב-USB או אלחוטי (אפשרויות מפתח).", style = MaterialTheme.typography.bodyMedium)
        Text("3. מריצים מהמחשב את הפקודה:", style = MaterialTheme.typography.bodyMedium)
        SelectionText(cmd)
        Row {
            TextButton(onClick = { clipboard.setText(AnnotatedString(cmd)) }) { Text("העתק פקודה") }
            TextButton(onClick = onRefresh) { Text("בדוק שוב") }
        }
        Text("4. מחזירים את החשבונות.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SelectionText(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(Spacing.s)
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
    var messageTone by remember { mutableStateOf(Tone.NEUTRAL) }
    GroupCard {
        Text(
            if (isSet) "מוגדר קוד. בלעדיו אי אפשר לפתוח את ההגדרות."
            else "אין קוד. כל מי שפותח את האפליקציה יכול לשנות הגדרות."
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(12) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            label = { Text(if (isSet) "קוד חדש" else "קוד (לפחות 4 ספרות)") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Button(onClick = {
                if (pin.length >= 4) {
                    val newPin = pin
                    // החלפת קוד קיים דורשת את הקוד הנוכחי
                    guard {
                        Pin.set(ctx, newPin)
                        isSet = true
                        pin = ""
                        message = "הקוד נשמר. אל תשכח אותו!"
                        messageTone = Tone.ALLOWED
                    }
                } else {
                    message = "צריך לפחות 4 ספרות"
                    messageTone = Tone.BLOCKED
                }
            }) { Text(if (isSet) "החלף קוד" else "הגדר קוד") }
            if (isSet) {
                TextButton(onClick = {
                    guard {
                        Pin.clear(ctx)
                        isSet = false
                        message = "הקוד הוסר"
                        messageTone = Tone.NEUTRAL
                    }
                }) { Text("הסר קוד") }
            }
        }
        message?.let { StatusMessage(it, messageTone) }
    }
}

@Composable
internal fun RemoveOwnerCard(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val guard = rememberPinGuard()
    GroupCard {
        Text("מסיר את כל הנעילות ואת הרשאת Device Owner. אחרי זה אפשר יהיה להסיר את האפליקציה.")
        OutlinedButton(
            onClick = { confirm = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) { Text("בטל Device Owner") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("לבטל את ההגנה?") },
            text = { Text("כל הנעילות יוסרו. הסינון עצמו ימשיך לעבוד עד שתעצור אותו.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        guard {
                            Protection.removeOwner(ctx)
                            onDone()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("בטל הגנה") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("השאר") } }
        )
    }
}
