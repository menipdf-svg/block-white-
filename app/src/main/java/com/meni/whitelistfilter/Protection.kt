package com.meni.whitelistfilter

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
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
    NO_DEBUGGING("חסימת אפשרויות מפתח", "חוסם ADB. שים לב: גם אתה לא תוכל להשתמש ב-ADB")
}

object Protection {
    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isOwner(ctx: Context): Boolean = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    private fun restriction(lock: Lock): String? = when (lock) {
        Lock.NO_VPN_CONFIG -> UserManager.DISALLOW_CONFIG_VPN
        Lock.NO_PRIVATE_DNS -> UserManager.DISALLOW_CONFIG_PRIVATE_DNS
        Lock.NO_SAFE_BOOT -> UserManager.DISALLOW_SAFE_BOOT
        Lock.NO_FACTORY_RESET -> UserManager.DISALLOW_FACTORY_RESET
        Lock.NO_ADD_USER -> UserManager.DISALLOW_ADD_USER
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
                    if (Build.VERSION.SDK_INT >= 30) {
                        d.setUserControlDisabledPackages(a, if (on) listOf(ctx.packageName) else emptyList())
                    }
                }
                else -> {
                    val r = restriction(lock)!!
                    if (on) d.addUserRestriction(a, r) else d.clearUserRestriction(a, r)
                    if (lock == Lock.NO_PRIVATE_DNS && on) d.setGlobalPrivateDnsModeOpportunistic(a)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** מסיר את כל הנעילות ואת הרשאת Device Owner */
    @Suppress("DEPRECATION")
    fun removeOwner(ctx: Context) {
        Lock.entries.forEach { set(ctx, it, false) }
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
