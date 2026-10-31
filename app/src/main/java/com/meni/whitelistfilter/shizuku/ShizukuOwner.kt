package com.meni.whitelistfilter.shizuku

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * התקנת Device Owner מתוך הטלפון דרך Shizuku (בלי מחשב).
 * Shizuku מריץ פקודות עם הרשאות shell, ומשם אפשר להריץ את אותה פקודה כמו ב-adb.
 */
object ShizukuOwner {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4242

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** האם שירות Shizuku רץ כרגע (הופעל דרך ניפוי אלחוטי או root) */
    fun running(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun granted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** מבקש מ-Shizuku הרשאה לאפליקציה. התוצאה חוזרת ב-onResult */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
            onResult(false)
        }
    }

    data class Result(val ok: Boolean, val output: String)

    /** מריץ את הפקודה dpm set-device-owner דרך Shizuku. לקרוא ממשימת רקע, לא מה-UI */
    fun setDeviceOwner(ctx: Context): Result {
        val cmd = "dpm set-device-owner ${ctx.packageName}/.AdminReceiver 2>&1"
        return try {
            // newProcess פרטית ב-Shizuku 13, ולכן קוראים לה דרך reflection
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val process = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            Result(code == 0 && out.contains("Success", ignoreCase = true), out.ifEmpty { "קוד יציאה $code" })
        } catch (e: Throwable) {
            Result(false, e.cause?.message ?: e.message ?: e.toString())
        }
    }
}
