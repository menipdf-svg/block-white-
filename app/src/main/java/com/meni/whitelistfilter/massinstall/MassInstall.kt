package com.meni.whitelistfilter.massinstall

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.meni.whitelistfilter.Protection
import com.meni.whitelistfilter.install.GuardState
import com.meni.whitelistfilter.install.InstallGuard
import com.meni.whitelistfilter.install.InstallSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** מאיפה מתקינים */
enum class MassKind { PLAY, APK }

enum class MassState(val title: String) {
    WAITING("ממתין"),
    WORKING("בתהליך"),
    DOWNLOADING("הורדה בגוגל פליי"),
    DONE("הותקן"),
    ALREADY("כבר מותקן"),
    SKIPPED("דולג"),
    FAILED("נכשל")
}

data class MassItem(
    val id: Int,
    /** שם חבילה (גוגל פליי) או כתובת (APK) */
    val target: String,
    /** שם החבילה, כשהוא ידוע */
    val pkg: String? = null,
    val state: MassState = MassState.WAITING,
    val note: String = ""
)

/**
 * התקנה המונית של רשימת אפליקציות.
 *
 * שני מקורות:
 * 1. גוגל פליי: אין התקנה שקטה אמיתית מגוגל פליי, אז פותחים את דף האפליקציה
 *    ושירות נגישות (MassInstallService) לוחץ "התקנה". גוגל פליי מורידה ומתקינה ברקע.
 * 2. קבצי APK: מורידים מקישור https ומתקינים דרך PackageInstaller.
 *    כ-Device Owner זה שקט לגמרי, בלי אישור ובלי לחיצות.
 *
 * כל אפליקציה חדשה עוברת אחר כך את InstallGuard (חסימה לפי קטגוריות), כרגיל.
 */
object MassInstall {

    val items = MutableStateFlow<List<MassItem>>(emptyList())
    val running = MutableStateFlow(false)

    @Volatile
    private var cancelled = false

    private val executor = Executors.newSingleThreadExecutor()
    private val added = ConcurrentHashMap.newKeySet<String>()

    private val packageRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val playIdRegex = Regex("[?&]id=([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)")

    private const val MAX_APK_BYTES = 600L * 1024 * 1024
    private const val PLAY_WAIT_MINUTES = 15L
    private const val CLICK_SETTLE_MS = 2500L

    /** מחזיר (שורות תקינות ללא כפילויות, שורות שנדחו) */
    fun parse(kind: MassKind, text: String): Pair<List<String>, List<String>> {
        val ok = LinkedHashSet<String>()
        val bad = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when (kind) {
                MassKind.PLAY -> {
                    // אפשר להדביק גם קישור מגוגל פליי, ושם החבילה נשלף ממנו
                    val pkg = playIdRegex.find(line)?.groupValues?.get(1)
                        ?: line.takeIf { packageRegex.matches(it) }
                    if (pkg != null) ok.add(pkg) else bad.add(line)
                }
                MassKind.APK -> {
                    if (line.startsWith("https://", ignoreCase = true) && line.length <= 2000) ok.add(line)
                    else bad.add(line)
                }
            }
        }
        return ok.toList() to bad
    }

    fun start(ctx: Context, kind: MassKind, targets: List<String>) {
        if (targets.isEmpty() || !running.compareAndSet(false, true)) return
        cancelled = false
        items.value = targets.mapIndexed { i, t ->
            MassItem(id = i, target = t, pkg = if (kind == MassKind.PLAY) t else null)
        }
        val app = ctx.applicationContext
        executor.execute {
            val since = System.currentTimeMillis()
            added.clear()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    intent.data?.schemeSpecificPart?.let { added.add(it) }
                }
            }
            val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                when (kind) {
                    MassKind.PLAY -> runPlay(app)
                    MassKind.APK -> runApk(app)
                }
                annotateGuard(app, since)
            } catch (e: Exception) {
                items.update { list ->
                    list.map {
                        if (it.state == MassState.WAITING || it.state == MassState.WORKING)
                            it.copy(state = MassState.FAILED, note = e.message ?: "שגיאה")
                        else it
                    }
                }
            } finally {
                try {
                    app.unregisterReceiver(receiver)
                } catch (_: Exception) {
                }
                running.value = false
            }
        }
    }

    fun cancel() {
        cancelled = true
        PlayBridge.cancel()
    }

    // ---------- עזרים ----------

    private fun set(id: Int, state: MassState, note: String = "", pkg: String? = null) {
        items.update { list ->
            list.map { if (it.id == id) it.copy(state = state, note = note, pkg = pkg ?: it.pkg) else it }
        }
    }

    private fun installedVersion(ctx: Context, pkg: String): Long? = try {
        ctx.packageManager.getPackageInfo(pkg, 0).longVersionCode
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun isThere(ctx: Context, pkg: String) = pkg in added || installedVersion(ctx, pkg) != null

    // ---------- גוגל פליי ----------

    private fun runPlay(ctx: Context) {
        val pending = LinkedHashMap<Int, String>()
        for (item in items.value) {
            val pkg = item.target
            if (cancelled) {
                set(item.id, MassState.SKIPPED, "בוטל")
                continue
            }
            if (pkg == ctx.packageName) {
                set(item.id, MassState.SKIPPED, "זו האפליקציה הזו")
                continue
            }
            if (installedVersion(ctx, pkg) != null) {
                set(item.id, MassState.ALREADY)
                continue
            }
            val service = MassInstallService.instance
            if (service == null) {
                set(item.id, MassState.FAILED, "שירות הנגישות לא פעיל")
                continue
            }
            set(item.id, MassState.WORKING, "פותח את גוגל פליי")
            when (PlayBridge.pressInstall(service, pkg)) {
                PlayResult.CLICKED -> {
                    set(item.id, MassState.DOWNLOADING)
                    pending[item.id] = pkg
                    // נותנים לגוגל פליי להתחיל את ההורדה לפני שעוברים לדף הבא
                    Thread.sleep(CLICK_SETTLE_MS)
                }
                PlayResult.NO_BUTTON ->
                    set(item.id, MassState.SKIPPED, "אין כפתור התקנה: בתשלום, לא תואם למכשיר או לא קיים")
                PlayResult.NOT_OPENED ->
                    set(item.id, MassState.FAILED, "גוגל פליי לא נפתחה")
            }
        }

        // ההורדות רצות בגוגל פליי ברקע. מחכים שיסתיימו
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(PLAY_WAIT_MINUTES)
        while (!cancelled && pending.isNotEmpty() && System.currentTimeMillis() < deadline) {
            val finished = pending.filterValues { isThere(ctx, it) }.keys
            finished.forEach { id ->
                set(id, MassState.DONE)
                pending.remove(id)
            }
            if (pending.isNotEmpty()) Thread.sleep(3000)
        }
        pending.keys.forEach { id ->
            set(
                id,
                MassState.FAILED,
                if (cancelled) "בוטל לפני שהסתיים" else "לא הותקן תוך $PLAY_WAIT_MINUTES דקות"
            )
        }
        MassInstallService.instance?.returnToApp()
    }

    // ---------- קבצי APK ----------

    private fun runApk(ctx: Context) {
        val dir = File(ctx.cacheDir, "massinstall").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        for (item in items.value) {
            if (cancelled) {
                set(item.id, MassState.SKIPPED, "בוטל")
                continue
            }
            val file = File(dir, "${item.id}.apk")
            try {
                set(item.id, MassState.WORKING, "מוריד")
                download(item.target, file)

                val info = ctx.packageManager.getPackageArchiveInfo(file.path, 0)
                if (info == null) {
                    set(item.id, MassState.FAILED, "קובץ APK לא תקין")
                    continue
                }
                val pkg = info.packageName
                // לא מתקינים את המסנן מעל עצמו: זה היה עוקף את "נעילת גרסה"
                if (pkg == ctx.packageName) {
                    set(item.id, MassState.SKIPPED, "לא מתקינים את האפליקציה הזו מעל עצמה", pkg)
                    continue
                }
                val have = installedVersion(ctx, pkg)
                if (have != null && have >= info.longVersionCode) {
                    set(item.id, MassState.ALREADY, "", pkg)
                    continue
                }
                set(item.id, MassState.WORKING, "מתקין", pkg)
                val error = install(ctx, file, pkg)
                if (error == null) set(item.id, MassState.DONE, "", pkg)
                else set(item.id, MassState.FAILED, error, pkg)
            } catch (e: Exception) {
                set(item.id, MassState.FAILED, e.message ?: "שגיאה")
            } finally {
                file.delete()
            }
        }
    }

    private fun download(url: String, out: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("שגיאת שרת ${conn.responseCode}")
            }
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        if (cancelled) throw IOException("בוטל")
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_APK_BYTES) throw IOException("הקובץ גדול מדי")
                        output.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** התקנה שקטה (Device Owner). מחזיר null בהצלחה, או הודעת שגיאה */
    private fun install(ctx: Context, file: File, pkg: String): String? {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(pkg)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val action = "com.meni.whitelistfilter.MASS_INSTALL_RESULT.$sessionId"
        val latch = CountDownLatch(1)
        val error = AtomicReference<String?>("זמן ההתקנה חלף")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                error.set(
                    when (status) {
                        PackageInstaller.STATUS_SUCCESS -> null
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> "דורש אישור ידני. האפליקציה אינה Device Owner"
                        else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            ?: "ההתקנה נכשלה ($status)"
                    }
                )
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val result = PendingIntent.getBroadcast(
                    ctx,
                    sessionId,
                    Intent(action).setPackage(ctx.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                session.commit(result.intentSender)
            }
            latch.await(180, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return e.message ?: "שגיאת התקנה"
        } finally {
            try {
                ctx.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
        if (error.get() != null) {
            try {
                installer.abandonSession(sessionId)
            } catch (_: Exception) {
            }
        }
        return error.get()
    }

    // ---------- חסימת קטגוריות ----------

    /**
     * InstallGuard בודק כל אפליקציה חדשה ועלול להסיר או להקפיא אותה.
     * מחכים רגע ומוסיפים הערה, כדי ש"הותקן" לא יטעה.
     */
    private fun annotateGuard(ctx: Context, since: Long) {
        if (!Protection.isOwner(ctx) || !InstallSettings.enabled(ctx)) return
        if (items.value.none { it.state == MassState.DONE }) return
        Thread.sleep(8000)
        val events = InstallGuard.events.value
        items.update { list ->
            list.map { item ->
                val e = events.firstOrNull { it.pkg == item.pkg && it.time >= since }
                if (item.state != MassState.DONE || e == null) {
                    item
                } else {
                    when (e.state) {
                        GuardState.REMOVED -> item.copy(note = "הוסרה על ידי חסימת הקטגוריות")
                        GuardState.HELD, GuardState.REMOVE_FAILED ->
                            item.copy(note = "הוקפאה על ידי חסימת הקטגוריות")
                        GuardState.CHECKING, GuardState.RETRY ->
                            item.copy(note = "עדיין בבדיקת קטגוריה")
                        GuardState.ALLOWED -> item
                    }
                }
            }
        }
    }
}

enum class PlayResult { CLICKED, NO_BUTTON, NOT_OPENED }

/** הגשר בין תהליך ההתקנה לבין שירות הנגישות, שרץ ב-thread הראשי */
object PlayBridge {
    @Volatile
    var target: String? = null

    @Volatile
    var seenPlay = false

    @Volatile
    var clicked = false

    @Volatile
    private var latch = CountDownLatch(0)

    /** פותח את דף האפליקציה וממתין עד 25 שניות שהשירות ילחץ "התקנה" */
    fun pressInstall(service: MassInstallService, pkg: String): PlayResult {
        seenPlay = false
        clicked = false
        val l = CountDownLatch(1)
        latch = l
        target = pkg
        try {
            service.openPlay(pkg)
        } catch (_: Exception) {
            target = null
            return PlayResult.NOT_OPENED
        }
        l.await(25, TimeUnit.SECONDS)
        target = null
        return when {
            clicked -> PlayResult.CLICKED
            seenPlay -> PlayResult.NO_BUTTON
            else -> PlayResult.NOT_OPENED
        }
    }

    fun onClicked() {
        clicked = true
        latch.countDown()
    }

    fun cancel() {
        latch.countDown()
    }
}
