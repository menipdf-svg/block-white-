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
import com.meni.whitelistfilter.Lock
import com.meni.whitelistfilter.Pin
import com.meni.whitelistfilter.Protection

@Composable
fun StatusScreen(onStart: () -> Unit, onStop: () -> Unit) {
    val ctx = LocalContext.current
    val running by FilterState.running.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val owner = remember(refresh) { Protection.isOwner(ctx) }
    val alwaysOn = remember(refresh) { Protection.isOn(ctx, Lock.ALWAYS_ON_VPN) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "מסנן רשימה לבנה",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        // ----- מצב הסינון -----
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running) "✅ הסינון פעיל" else "⛔ הסינון כבוי",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (alwaysOn) Text("נעול: עולה אוטומטית", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!running) Button(onClick = onStart) { Text("הפעל") }
                    else if (!alwaysOn) OutlinedButton(onClick = onStop) { Text("עצור") }
                }
            }
        }

        // ----- Device Owner -----
        item { SectionTitle("הגנה מפני עקיפה") }
        if (!owner) {
            item { OwnerSetupCard { refresh++ } }
        } else {
            item {
                Text(
                    "✅ האפליקציה מוגדרת כ-Device Owner",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            items(Lock.entries.size) { i ->
                val lock = Lock.entries[i]
                var on by remember(refresh) { mutableStateOf(Protection.isOn(ctx, lock)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(lock.title, style = MaterialTheme.typography.bodyLarge)
                        Text(lock.desc, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = on, onCheckedChange = {
                        if (lock == Lock.ALWAYS_ON_VPN && it && !running) onStart()
                        Protection.set(ctx, lock, it)
                        on = Protection.isOn(ctx, lock)
                        refresh++
                    })
                }
            }
        }

        // ----- קוד -----
        item { SectionTitle("קוד נעילה") }
        item { PinCard() }

        if (owner) {
            item { SectionTitle("ביטול ההגנה") }
            item { RemoveOwnerCard { refresh++ } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun OwnerSetupCard(onRefresh: () -> Unit) {
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
private fun PinCard() {
    val ctx = LocalContext.current
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
                        Pin.set(ctx, pin)
                        isSet = true
                        pin = ""
                        message = "הקוד נשמר. אל תשכח אותו!"
                    } else message = "צריך לפחות 4 ספרות"
                }) { Text(if (isSet) "החלף קוד" else "הגדר קוד") }
                if (isSet) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        Pin.clear(ctx)
                        isSet = false
                        message = "הקוד הוסר"
                    }) { Text("הסר קוד") }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun RemoveOwnerCard(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
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
                    Protection.removeOwner(ctx)
                    confirm = false
                    onDone()
                }) { Text("בטל הגנה") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("השאר") } }
        )
    }
}
