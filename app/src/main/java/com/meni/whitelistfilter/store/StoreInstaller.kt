package com.meni.whitelistfilter.store

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/** הורדה, אימות והתקנה שקטה של אפליקציה מהקטלוג (Device Owner לא צריך אישור) */
object StoreInstaller {

    /** חבילה -> הודעת מצב להצגה (מוריד, מתקין, נכשל…) */
    val status = MutableStateFlow<Map<String, String>>(emptyMap())

    const val ACTION_RESULT = "com.meni.whitelistfilter.STORE_INSTALL_RESULT"

    fun setStatus(pkg: String, text: String?) {
        status.update { if (text == null) it - pkg else it + (pkg to text) }
    }

    fun installedVersion(ctx: Context, pkg: String): Long? = try {
        ctx.packageManager.getPackageInfo(pkg, 0).longVersionCode
    } catch (_: Exception) {
        null
    }

    fun install(ctx: Context, app: StoreApp) {
        val c = ctx.applicationContext
        if (status.value.containsKey(app.pkg)) return
        thread {
            try {
                setStatus(app.pkg, "מוריד…")
                val file = download(c, app)
                setStatus(app.pkg, "בודק…")
                val actual = sha256(file)
                if (!actual.equals(app.sha256, ignoreCase = true)) {
                    file.delete()
                    setStatus(app.pkg, "נכשל: הקובץ שהורד לא תואם ל-SHA-256 בקטלוג")
                    return@thread
                }
                val info = c.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                if (info == null || info.packageName != app.pkg) {
                    file.delete()
                    setStatus(app.pkg, "נכשל: הקובץ אינו של ${app.pkg}")
                    return@thread
                }
                setStatus(app.pkg, "מתקין…")
                commit(c, app, file)
            } catch (e: Exception) {
                setStatus(app.pkg, "נכשל: ${e.message ?: "שגיאה"}")
            }
        }
    }

    private fun download(ctx: Context, app: StoreApp): File {
        val dir = File(ctx.cacheDir, "store").apply { mkdirs() }
        val file = File(dir, "${app.pkg}.apk")
        val conn = URL(app.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        if (conn.responseCode != 200) error("ההורדה נכשלה (${conn.responseCode})")
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun commit(ctx: Context, app: StoreApp, file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.pkg)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, StoreResultReceiver::class.java)
                .setAction(ACTION_RESULT)
                .putExtra("pkg", app.pkg)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

/** תוצאת ההתקנה חוזרת לכאן */
class StoreResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // אם המכשיר עדיין מבקש אישור (למשל כשהאפליקציה אינה Device Owner)
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(confirm)
                    StoreInstaller.setStatus(pkg, "ממתין לאישור במסך")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                StoreInstaller.setStatus(pkg, null)
                File(ctx.cacheDir, "store/$pkg.apk").delete()
                StoreCatalog.bump()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "שגיאה"
                StoreInstaller.setStatus(pkg, "נכשל: $msg")
            }
        }
    }
}
