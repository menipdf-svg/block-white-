package com.meni.whitelistfilter.store

import android.content.Context
import android.content.pm.ApplicationInfo
import com.meni.whitelistfilter.Protection
import com.meni.whitelistfilter.install.InstallGuard

/**
 * מצב "רק מהחנות": כל אפליקציה חדשה שאינה בקטלוג מוסרת מיד.
 * רק אפליקציות שהותקנו אחרי ההפעלה נבדקות, כדי לא לפגוע במה שכבר מותקן.
 */
object StoreGuard {
    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("only_store", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().apply {
            putBoolean("only_store", on)
            if (on) putLong("only_store_at", System.currentTimeMillis())
        }.apply()
    }

    private fun since(ctx: Context) = prefs(ctx).getLong("only_store_at", Long.MAX_VALUE)

    /** נקרא כשהותקנה חבילה חדשה. מחזיר true אם היא הוסרה */
    fun onAdded(ctx: Context, pkg: String): Boolean {
        if (!enabled(ctx) || !Protection.isOwner(ctx)) return false
        if (pkg == ctx.packageName || pkg in StoreCatalog.allowedPackages(ctx)) return false
        val system = try {
            (ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Exception) {
            true
        }
        if (system) return false
        InstallGuard.remove(ctx, pkg)
        return true
    }

    /** מעבר על מה שהותקן בזמן שהסינון לא רץ */
    @Suppress("DEPRECATION")
    fun sweep(ctx: Context) {
        if (!enabled(ctx) || !Protection.isOwner(ctx)) return
        val pm = ctx.packageManager
        val allowed = StoreCatalog.allowedPackages(ctx)
        for (info in pm.getInstalledApplications(0)) {
            val pkg = info.packageName
            if (pkg == ctx.packageName || pkg in allowed) continue
            if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            val installedAt = try {
                pm.getPackageInfo(pkg, 0).firstInstallTime
            } catch (_: Exception) {
                0L
            }
            if (installedAt > since(ctx)) InstallGuard.remove(ctx, pkg)
        }
    }
}
