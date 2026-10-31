package com.meni.whitelistfilter.ui

import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.meni.whitelistfilter.shizuku.ShizukuOwner
import kotlin.concurrent.thread

/** התקנת Device Owner מתוך הטלפון דרך Shizuku. מוצג בכרטיס ההגדרה החד-פעמית */
@Composable
internal fun ShizukuSection(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val installed = remember(tick) { ShizukuOwner.installed(ctx) }
    val running = remember(tick) { ShizukuOwner.running() }
    val granted = remember(tick) { running && ShizukuOwner.granted() }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var tone by remember { mutableStateOf(Tone.NEUTRAL) }

    Text("בלי מחשב: דרך Shizuku", style = MaterialTheme.typography.titleSmall)
    Text(
        "Shizuku מריץ את אותה פקודה מתוך הטלפון. צריך להתקין אותו, להפעיל ניפוי אלחוטי ולהפעיל את השירות. " +
            "גם כאן החשבונות חייבים להיות מוסרים לפני ההתקנה.",
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        when {
            !installed -> "Shizuku לא מותקן"
            !running -> "Shizuku מותקן אבל השירות לא פועל"
            !granted -> "השירות פועל, חסרה הרשאה לאפליקציה"
            else -> "הכול מוכן להתקנה"
        },
        style = MaterialTheme.typography.bodySmall
    )
    Row {
        if (!installed) {
            TextButton(onClick = {
                // קודם גוגל פליי, ואם אין אפליקציית חנות פותחים את דף ההורדות ב-GitHub
                val play = Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=${ShizukuOwner.SHIZUKU_PACKAGE}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(play)
                } catch (_: Exception) {
                    ctx.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) { Text("הורד את Shizuku") }
        }
        if (installed && !running) {
            TextButton(onClick = {
                ctx.packageManager.getLaunchIntentForPackage(ShizukuOwner.SHIZUKU_PACKAGE)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { ctx.startActivity(it) }
            }) { Text("פתח את Shizuku") }
        }
        if (running && !granted) {
            TextButton(onClick = { ShizukuOwner.requestPermission { tick++ } }) { Text("אשר הרשאה") }
        }
        if (granted) {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    message = null
                    thread {
                        val r = ShizukuOwner.setDeviceOwner(ctx)
                        android.os.Handler(ctx.mainLooper).post {
                            busy = false
                            tone = if (r.ok) Tone.ALLOWED else Tone.BLOCKED
                            message = if (r.ok) "הותקן בהצלחה" else r.output
                            if (r.ok) onDone()
                            tick++
                        }
                    }
                }
            ) { Text(if (busy) "מתקין…" else "התקן Device Owner") }
        }
        TextButton(onClick = { tick++ }) { Text("בדוק שוב") }
    }
    message?.let { StatusMessage(it, tone) }
}
