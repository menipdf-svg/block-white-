package com.meni.whitelistfilter

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import java.security.MessageDigest

class AdminReceiver : DeviceAdminReceiver()

enum class Lock(val title: String, val desc: String) {
    ALWAYS_ON_VPN("VPN תמיד פעיל + חסימה", "המסנן עולה לבד, ובלעדיו אין אינטרנט בכלל"),
    NO_VPN_CONFIG("נעילת הגדרות VPN", "אי אפשר לכבות או להחליף VPN מההגדרות"),
    NO_PRIVATE_DNS("נעילת DNS פרטי", "מונע עקיפה דרך הגדרת DNS פרטי"),
    NO_UNINSTALL("מניעת הסרה ועצירה", "אי אפשר להסיר, לעצור או למחוק נתונים של המסנן"),
    NO_SAFE_BOOT("חסימת מצב בטוח", "מונע הפעלה במצב בטוח שעוקף אפליקציות"),
    NO_FACTORY_RESET("חסימת איפוס יצרן", "אי אפשר לאפס את המכשיר מההגדרות"),
    NO_ADD_USER("חסימת משתמשים נוספים", "אי אפשר ליצור משתמש חדש בלי סינון"),
    NO_UNKNOWN_SOURCES("חסימת התקנה ממקורות לא ידועים", "אפשר להתקין רק דרך חנות האפליקציות (Google Play)"),
    NO_DEBUGGING("חסימת אפשרויות מפתח", "חוסם ADB. שים לב: גם אתה לא תוכל להשתמש ב-ADB")
}

/** מדיניות שנכפית על כרום דרך Device Owner (הגדרות מנוהלות — אי אפשר לשנות אותן בתוך כרום) */
enum class ChromeLock(val title: String, val desc: String, val key: String) {
    SAFE_BROWSING(
        "Safe Browsing מוגבר בכרום",
        "כרום מזהיר וחוסם אתרים מסוכנים, ואי אפשר לכבות את זה בהגדרות כרום",
        "SafeBrowsingProtectionLevel"
    ),
    SAFE_SEARCH(
        "חיפוש בטוח בכרום (SafeSearch)",
        "גוגל מסננת תוצאות חיפוש בוטות, ואי אפשר לכבות את זה",
        "ForceGoogleSafeSearch"
    )
}

object Protection {
    const val CHROME_PACKAGE = "com.android.chrome"

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isOwner(ctx: Context): Boolean = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /** האם האפליקציה מוקפאת (לא ניתן להפעיל אותה) */
    fun isAppSuspended(ctx: Context, pkg: String): Boolean = try {
        dpm(ctx).isPackageSuspended(admin(ctx), pkg)
    } catch (_: Exception) {
        false
    }

    /** מקפיא או משחרר אפליקציה, בלי קשר לאינטרנט. דורש Device Owner. מחזיר true אם הצליח */
    fun setAppSuspended(ctx: Context, pkg: String, suspended: Boolean): Boolean {
        if (pkg == ctx.packageName) return false
        return try {
            dpm(ctx).setPackagesSuspended(admin(ctx), arrayOf(pkg), suspended).isEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun restriction(lock: Lock): String? = when (lock) {
        Lock.NO_VPN_CONFIG -> UserManager.DISALLOW_CONFIG_VPN
        Lock.NO_PRIVATE_DNS -> UserManager.DISALLOW_CONFIG_PRIVATE_DNS
        Lock.NO_SAFE_BOOT -> UserManager.DISALLOW_SAFE_BOOT
        Lock.NO_FACTORY_RESET -> UserManager.DISALLOW_FACTORY_RESET
        Lock.NO_ADD_USER -> UserManager.DISALLOW_ADD_USER
        Lock.NO_UNKNOWN_SOURCES -> UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
        Lock.NO_DEBUGGING -> UserManager.DISALLOW_DEBUGGING_FEATURES
        else -> null
    }

    fun isOn(ctx: Context, lock: Lock): Boolean {
        if (!isOwner(ctx)) return false
        val d = dpm(ctx)
        val a = admin(ctx)
        return try {
            when (lock) {
                Lock.ALWAYS_ON_VPN -> d.getAlwaysOnVpnPackage(a) == ctx.packageName
                Lock.NO_UNINSTALL -> d.isUninstallBlocked(a, ctx.packageName)
                else -> d.getUserRestrictions(a).getBoolean(restriction(lock))
            }
        } catch (_: Exception) {
            false
        }
    }

    fun set(ctx: Context, lock: Lock, on: Boolean): Boolean {
        if (!isOwner(ctx)) return false
        val d = dpm(ctx)
        val a = admin(ctx)
        return try {
            when (lock) {
                Lock.ALWAYS_ON_VPN ->
                    d.setAlwaysOnVpnPackage(a, if (on) ctx.packageName else null, on)
                Lock.NO_UNINSTALL -> {
                    d.setUninstallBlocked(a, ctx.packageName, on)
                    applyUserControl(ctx)
                }
                else -> {
                    val r = restriction(lock)!!
                    if (on) d.addUserRestriction(a, r) else d.clearUserRestriction(a, r)
                    if (lock == Lock.NO_DEBUGGING && on) disableAdb(ctx)
                    if (lock == Lock.NO_PRIVATE_DNS && on && forcedDnsHost(ctx) == null) d.setGlobalPrivateDnsModeOpportunistic(a)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** מכבה ניפוי באגים ואפשרויות מפתח מיד (ההגבלה עצמה מונעת להפעיל אותם שוב) */
    @Suppress("DEPRECATION")
    private fun disableAdb(ctx: Context) {
        val d = dpm(ctx)
        val a = admin(ctx)
        for (key in listOf(Settings.Global.ADB_ENABLED, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)) {
            try {
                d.setGlobalSetting(a, key, "0")
            } catch (_: Exception) {
            }
        }
    }

    // ---------- אפליקציות מוגנות (למשל TextPlay ו-Spotify) ----------

    private fun securityPrefs(ctx: Context) = ctx.getSharedPreferences("security", Context.MODE_PRIVATE)

    /** אפליקציות שאי אפשר להסיר, לעצור או למחוק להן נתונים */
    fun protectedPackages(ctx: Context): Set<String> =
        securityPrefs(ctx).getStringSet("protected", emptySet()).orEmpty()

    fun isProtected(ctx: Context, pkg: String): Boolean {
        if (!isOwner(ctx)) return false
        return try {
            dpm(ctx).isUninstallBlocked(admin(ctx), pkg)
        } catch (_: Exception) {
            false
        }
    }

    fun setProtected(ctx: Context, pkg: String, on: Boolean): Boolean {
        if (!isOwner(ctx)) return false
        return try {
            dpm(ctx).setUninstallBlocked(admin(ctx), pkg, on)
            val set = protectedPackages(ctx).toMutableSet().apply { if (on) add(pkg) else remove(pkg) }
            securityPrefs(ctx).edit().putStringSet("protected", set).apply()
            applyUserControl(ctx)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** "עצירה בכוח" ו"מחיקת נתונים" חסומים למסנן (אם נעול) ולכל האפליקציות המוגנות */
    private fun applyUserControl(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val d = dpm(ctx)
        val a = admin(ctx)
        val list = buildList {
            if (d.isUninstallBlocked(a, ctx.packageName)) add(ctx.packageName)
            addAll(protectedPackages(ctx))
        }
        d.setUserControlDisabledPackages(a, list)
    }

    // ---------- כרום ----------

    fun isChromeOn(ctx: Context, lock: ChromeLock): Boolean {
        if (!isOwner(ctx)) return false
        return try {
            val b = dpm(ctx).getApplicationRestrictions(admin(ctx), CHROME_PACKAGE)
            when (lock) {
                ChromeLock.SAFE_BROWSING -> b.getInt(lock.key, 0) == 2
                ChromeLock.SAFE_SEARCH -> b.getBoolean(lock.key, false)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun setChrome(ctx: Context, lock: ChromeLock, on: Boolean): Boolean {
        if (!isOwner(ctx)) return false
        return try {
            val d = dpm(ctx)
            val a = admin(ctx)
            val b = Bundle(d.getApplicationRestrictions(a, CHROME_PACKAGE))
            if (!on) b.remove(lock.key) else when (lock) {
                ChromeLock.SAFE_BROWSING -> b.putInt(lock.key, 2) // 2 = מוגבר
                ChromeLock.SAFE_SEARCH -> b.putBoolean(lock.key, true)
            }
            d.setApplicationRestrictions(a, CHROME_PACKAGE, b)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isChromeInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getApplicationInfo(CHROME_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    // ---------- DNS נכפה (DNS פרטי של המערכת, DoT) ----------

    /** שם השרת שנכפה כרגע, או null אם אין כפייה */
    fun forcedDnsHost(ctx: Context): String? {
        if (!isOwner(ctx)) return null
        return try {
            val d = dpm(ctx)
            val a = admin(ctx)
            if (d.getGlobalPrivateDnsMode(a) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME) {
                d.getGlobalPrivateDnsHost(a)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * host = שם שרת DNS פרטי לכפייה. null = ביטול הכפייה (חזרה למצב אוטומטי).
     * הבדיקה של השרת היא פעולת רשת, ולכן לא קוראים לזה מה-thread הראשי.
     */
    fun setForcedDns(ctx: Context, host: String?): Boolean {
        if (!isOwner(ctx)) return false
        return try {
            val d = dpm(ctx)
            val a = admin(ctx)
            if (host == null) {
                d.setGlobalPrivateDnsModeOpportunistic(a)
                true
            } else {
                d.setGlobalPrivateDnsModeSpecifiedHost(a, host) == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------- חסימת שימוש ב-VPN של אפליקציות אחרות ----------

    fun vpnBlocked(ctx: Context): Boolean = securityPrefs(ctx).getBoolean("vpn_blocked", false)

    /** אפליקציות (לא מערכת, לא אנחנו) שמצהירות על שירות VPN */
    @Suppress("DEPRECATION")
    fun vpnApps(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val found = try {
            pm.queryIntentServices(android.content.Intent("android.net.VpnService"), PackageManager.MATCH_ALL)
        } catch (_: Exception) {
            emptyList()
        }
        return found.mapNotNull { it.serviceInfo?.applicationInfo }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }
            .filter { it != ctx.packageName }
            .toSet()
    }

    fun isVpnApp(ctx: Context, pkg: String) = pkg in vpnApps(ctx)

    private fun suspend(ctx: Context, pkgs: Collection<String>, on: Boolean) {
        if (pkgs.isEmpty()) return
        try {
            dpm(ctx).setPackagesSuspended(admin(ctx), pkgs.toTypedArray(), on)
        } catch (_: Exception) {
        }
    }

    /**
     * חסימה: אי אפשר להגדיר VPN במערכת, וכל אפליקציית VPN מותקנת מוקפאת (ולכן גם נעצרת).
     * אפליקציות VPN שיותקנו אחר כך מוקפאות אוטומטית על ידי InstallGuard.
     */
    fun setVpnBlocked(ctx: Context, on: Boolean): Boolean {
        if (!isOwner(ctx)) return false
        return try {
            val d = dpm(ctx)
            val a = admin(ctx)
            securityPrefs(ctx).edit().putBoolean("vpn_blocked", on).apply()
            if (on) {
                d.addUserRestriction(a, UserManager.DISALLOW_CONFIG_VPN)
                suspend(ctx, vpnApps(ctx) - protectedPackages(ctx), true)
            } else {
                // מחזירים רק אם נעילת "הגדרות VPN" הרגילה כבויה
                if (!isOn(ctx, Lock.NO_VPN_CONFIG)) d.clearUserRestriction(a, UserManager.DISALLOW_CONFIG_VPN)
                suspend(ctx, vpnApps(ctx), false)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** מקפיא אפליקציות VPN שהותקנו כשהחסימה פעילה. נקרא בהתקנה ובעליית השירות */
    fun enforceVpnBlock(ctx: Context) {
        if (!isOwner(ctx) || !vpnBlocked(ctx)) return
        suspend(ctx, vpnApps(ctx) - protectedPackages(ctx), true)
    }

    // ---------- הגנת איפוס יצרן (FRP) ----------

    /** המייל שנבחר להגנה (להצגה בלבד) */
    fun frpEmail(ctx: Context): String? = securityPrefs(ctx).getString("frp_email", null)

    fun isFrpOn(ctx: Context): Boolean {
        if (!isOwner(ctx) || Build.VERSION.SDK_INT < 30) return false
        return try {
            val p = dpm(ctx).getFactoryResetProtectionPolicy(admin(ctx))
            p != null && p.isFactoryResetProtectionEnabled && p.factoryResetProtectionAccounts.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /** נותן לעצמו את ההרשאה לקרוא חשבונות (Device Owner יכול לתת הרשאות לעצמו) */
    fun grantAccountsPermission(ctx: Context) {
        try {
            dpm(ctx).setPermissionGrantState(
                admin(ctx), ctx.packageName, android.Manifest.permission.GET_ACCOUNTS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        } catch (_: Exception) {
        }
    }

    /**
     * מפעיל FRP כך שרק החשבון עם המזהה הזה יפתח את המכשיר אחרי איפוס.
     * accountId = null מבטל את המדיניות. השליפה של המזהה היא פעולת רשת, ולכן לא מה-thread הראשי.
     */
    fun setFrp(ctx: Context, accountId: String?, email: String?): Boolean {
        if (!isOwner(ctx) || Build.VERSION.SDK_INT < 30) return false
        return try {
            val policy = accountId?.let {
                android.app.admin.FactoryResetProtectionPolicy.Builder()
                    .setFactoryResetProtectionAccounts(listOf(it))
                    .setFactoryResetProtectionEnabled(true)
                    .build()
            }
            dpm(ctx).setFactoryResetProtectionPolicy(admin(ctx), policy)
            securityPrefs(ctx).edit().apply {
                if (accountId != null) putString("frp_email", email) else remove("frp_email")
            }.apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** מסיר את כל הנעילות ואת הרשאת Device Owner */
    @Suppress("DEPRECATION")
    fun removeOwner(ctx: Context) {
        Lock.entries.forEach { set(ctx, it, false) }
        ChromeLock.entries.forEach { setChrome(ctx, it, false) }
        setForcedDns(ctx, null)
        setVpnBlocked(ctx, false)
        setFrp(ctx, null, null)
        protectedPackages(ctx).forEach { setProtected(ctx, it, false) }
        try {
            dpm(ctx).clearDeviceOwnerApp(ctx.packageName)
        } catch (_: Exception) {
        }
    }

    fun adbCommand(ctx: Context) = "adb shell dpm set-device-owner ${ctx.packageName}/.AdminReceiver"
}

object Pin {
    private const val PREFS = "security"
    private const val KEY = "pin_hash"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("wlf:$pin").toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun isSet(ctx: Context) = prefs(ctx).getString(KEY, null) != null

    fun check(ctx: Context, pin: String) = prefs(ctx).getString(KEY, null) == hash(pin)

    fun set(ctx: Context, pin: String) = prefs(ctx).edit().putString(KEY, hash(pin)).apply()

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()
}
