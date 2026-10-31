package com.meni.whitelistfilter.install

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.meni.whitelistfilter.Protection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class GuardState(val title: String) {
    CHECKING("בבדיקה"),
    RETRY("ממתין לאינטרנט, ינסה שוב"),
    ALLOWED("מותרת"),
    REMOVED("הוסרה"),
    HELD("הוקפאה"),
    REMOVE_FAILED("ההסרה נכשלה, נשארה מוקפאת")
}

data class GuardEvent(
    val pkg: String,
    val label: String,
    val category: String?,
    val state: GuardState,
    val time: Long
)

/**
 * חסימת התקנות לפי קטגוריה ב-Google Play.
 *
 * אנדרואיד לא מאפשר לחסום התקנה מראש לפי קטגוריה, אז עובדים בשיטת "הסגר":
 * 1. אפליקציה חדשה מותקנת -> מוקפאת מיד (אי אפשר להפעיל אותה)
 * 2. בודקים את הקטגוריה שלה בדף ה-Google Play
 * 3. קטגוריה מותרת -> משחררים. קטגוריה חסומה -> מסירים (או משאירים מוקפאת)
 * 4. אין אינטרנט או שהבדיקה נכשלה -> נשארת מוקפאת ומנסים שוב. אף פעם לא משחררים בלי תשובה
 *
 * דורש Device Owner.
 */
object InstallGuard {

    val events = MutableStateFlow<List<GuardEvent>>(emptyList())

    /** עולה בכל שינוי, כדי שהמסך ירענן */
    val version = MutableStateFlow(0)

    private const val ACTION_UNINSTALL_DONE = "com.meni.whitelistfilter.UNINSTALL_DONE"
    private const val MAX_RETRY_SECONDS = 600L

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun bump() {
        version.update { it + 1 }
    }

    // ---------- אירועי התקנה (מגיעים מ-InstallReceiver) ----------

    fun onPackageAdded(ctx: Context, pkg: String, replacing: Boolean) {
        if (replacing) return // עדכון של אפליקציה קיימת
        val app = ctx.applicationContext
        executor.execute {
            // חסימת VPN פועלת בנפרד מחסימת הקטגוריות
            if (Protection.vpnBlocked(app) && Protection.isVpnApp(app, pkg)) Protection.enforceVpnBlock(app)
            process(app, pkg, 0)
        }
    }

    fun onPackageRemoved(ctx: Context, pkg: String) {
        InstallSettings.forget(ctx.applicationContext, pkg)
        bump()
    }

    /**
     * עובר על אפליקציות שממתינות לבדיקה, ועל אפליקציות שהותקנו כשהסינון לא רץ
     * (אחרי ההפעלה של החסימה). נקרא בעליית השירות וכשמפעילים את החסימה.
     */
    @Suppress("DEPRECATION")
    fun sweep(ctx: Context) {
        val app = ctx.applicationContext
        executor.execute {
            Protection.enforceVpnBlock(app)
            if (!InstallSettings.enabled(app) || !Protection.isOwner(app)) return@execute
            val pm = app.packageManager
            val since = InstallSettings.enabledAt(app)
            val pending = InstallSettings.pending(app)
            val verified = InstallSettings.verified(app)
            val held = InstallSettings.held(app)
            for (info in pm.getInstalledApplications(0)) {
                val pkg = info.packageName
                if (pkg in pending) {
                    process(app, pkg, 0)
                } else if (pkg !in verified && pkg !in held && isUserApp(app, info)) {
                    val installedAt = try {
                        pm.getPackageInfo(pkg, 0).firstInstallTime
                    } catch (_: Exception) {
                        0L
                    }
                    if (installedAt > since) process(app, pkg, 0)
                }
            }
        }
    }

    // ---------- פעולות מהמסך ----------

    /** שחרור ידני של אפליקציה מוקפאת — לא תיבדק שוב */
    fun allow(ctx: Context, pkg: String) {
        val app = ctx.applicationContext
        executor.execute {
            InstallSettings.addAllowed(app, pkg)
            InstallSettings.removeHeld(app, pkg)
            InstallSettings.removePending(app, pkg)
            setSuspended(app, pkg, false)
            record(app, pkg, null, GuardState.ALLOWED)
            bump()
        }
    }

    /** הסרה ידנית של אפליקציה מוקפאת */
    fun remove(ctx: Context, pkg: String) {
        val app = ctx.applicationContext
        executor.execute {
            uninstall(app, pkg)
            bump()
        }
    }

    // ---------- הלוגיקה ----------

    private fun process(ctx: Context, pkg: String, attempt: Int) {
        if (!InstallSettings.enabled(ctx) || !Protection.isOwner(ctx)) return
        if (!isHandled(ctx, pkg)) return
        if (!inFlight.add(pkg)) return
        try {
            // קודם כל הסגר: רושמים שממתינה, ומקפיאים
            InstallSettings.addPending(ctx, pkg)
            setSuspended(ctx, pkg, true)
            record(ctx, pkg, null, GuardState.CHECKING)

            // דפדפן מזוהה מקומית (בלי אינטרנט), לפני הבדיקה בגוגל פליי
            val category = if (InstallSettings.isBlocked(ctx, PlayCategories.BROWSER) && isBrowser(ctx, pkg)) {
                PlayCategories.BROWSER
            } else {
                categoryFor(ctx, pkg)
            }
            if (category == null) {
                record(ctx, pkg, null, GuardState.RETRY)
                val delay = minOf(MAX_RETRY_SECONDS, 30L shl minOf(attempt, 5))
                executor.schedule(Runnable { process(ctx, pkg, attempt + 1) }, delay, TimeUnit.SECONDS)
                return
            }

            if (InstallSettings.isBlocked(ctx, category)) {
                if (InstallSettings.uninstall(ctx)) {
                    uninstall(ctx, pkg, category)
                } else {
                    InstallSettings.addHeld(ctx, pkg, category)
                    InstallSettings.removePending(ctx, pkg)
                    record(ctx, pkg, category, GuardState.HELD)
                }
            } else {
                setSuspended(ctx, pkg, false)
                InstallSettings.addVerified(ctx, pkg)
                InstallSettings.removePending(ctx, pkg)
                record(ctx, pkg, category, GuardState.ALLOWED)
            }
        } finally {
            inFlight.remove(pkg)
            bump()
        }
    }

    /** מחזיר את הקטגוריה, או null אם אי אפשר לדעת כרגע */
    private fun categoryFor(ctx: Context, pkg: String): String? {
        InstallSettings.cachedCategory(ctx, pkg)?.let { return it }
        return when (val r = PlayLookup.lookup(pkg)) {
            is LookupResult.Found -> {
                InstallSettings.cacheCategory(ctx, pkg, r.category)
                r.category
            }
            LookupResult.NotOnPlay -> PlayCategories.NOT_ON_PLAY
            LookupResult.Failed -> null
        }
    }

    /** האם בכלל לטפל באפליקציה הזו */
    @Suppress("DEPRECATION")
    private fun isHandled(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName) return false
        val info = try {
            ctx.packageManager.getApplicationInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            InstallSettings.forget(ctx, pkg)
            return false
        }
        if (!isUserApp(ctx, info)) return false
        if (pkg in InstallSettings.allowed(ctx)) return false
        if (pkg in Protection.protectedPackages(ctx)) return false
        // הפיירפוקס המסונן של הפרויקט הזה — אף פעם לא נחסם
        if (pkg.startsWith("org.mozilla.fenix")) return false
        return true
    }

    /**
     * דפדפן = אפליקציה שרשומה לפתוח כל כתובת http/https (לא רק אתר מסוים).
     * כך מזוהים גם דפדפנים שגוגל פליי מסווגת בקטגוריות שונות.
     */
    @Suppress("DEPRECATION")
    private fun isBrowser(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        return listOf("https://www.example.com", "http://www.example.com").any { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .any { it.activityInfo?.packageName == pkg }
        }
    }

    private fun isUserApp(ctx: Context, info: ApplicationInfo): Boolean =
        info.packageName != ctx.packageName && (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0

    private fun setSuspended(ctx: Context, pkg: String, suspended: Boolean): Boolean = try {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        dpm.setPackagesSuspended(Protection.admin(ctx), arrayOf(pkg), suspended).isEmpty()
    } catch (_: Exception) {
        false
    }

    /** הסרה שקטה — Device Owner לא צריך אישור מהמשתמש */
    private fun uninstall(ctx: Context, pkg: String, category: String? = null) {
        try {
            val done = PendingIntent.getBroadcast(
                ctx,
                pkg.hashCode(),
                Intent(ACTION_UNINSTALL_DONE).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            ctx.packageManager.packageInstaller.uninstall(pkg, done.intentSender)
            record(ctx, pkg, category, GuardState.REMOVED)
            // אם אחרי כמה שניות האפליקציה עדיין שם — ההסרה נכשלה, והיא נשארת מוקפאת
            executor.schedule(Runnable { verifyRemoved(ctx, pkg, category) }, 20, TimeUnit.SECONDS)
        } catch (_: Exception) {
            failedRemoval(ctx, pkg, category)
        }
    }

    private fun verifyRemoved(ctx: Context, pkg: String, category: String?) {
        val stillThere = try {
            ctx.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (stillThere) failedRemoval(ctx, pkg, category) else InstallSettings.forget(ctx, pkg)
        bump()
    }

    private fun failedRemoval(ctx: Context, pkg: String, category: String?) {
        setSuspended(ctx, pkg, true)
        InstallSettings.addHeld(ctx, pkg, category ?: "")
        InstallSettings.removePending(ctx, pkg)
        record(ctx, pkg, category, GuardState.REMOVE_FAILED)
    }

    private fun record(ctx: Context, pkg: String, category: String?, state: GuardState) {
        val label = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        val entry = GuardEvent(pkg, label, category, state, System.currentTimeMillis())
        events.update { list -> (listOf(entry) + list.filterNot { it.pkg == pkg }).take(50) }
    }
}

/** מקבל הודעות התקנה והסרה מהמערכת. נרשם בזמן ריצה מתוך שירות ה-VPN */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart ?: return
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED ->
                InstallGuard.onPackageAdded(
                    context,
                    pkg,
                    intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                )
            Intent.ACTION_PACKAGE_FULLY_REMOVED -> InstallGuard.onPackageRemoved(context, pkg)
        }
    }
}
