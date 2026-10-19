package com.meni.whitelistfilter.image

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * מטמון בדיסק של תמונות שכבר נבדקו — נשמר גם אחרי סגירת האפליקציה.
 * לכל תמונה נשמרת התוצאה הסופית שהדפדפן קיבל: המקור (אם היא תקינה) או המשטח החסום.
 * המפתח כולל את הגדרות הסינון, כך ששינוי הגדרה גורם לבדיקה מחדש.
 */
object DiskImageCache {
    private const val DIR = "image_cache"
    private const val MAX_BYTES = 150L * 1024 * 1024
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private var dir: File? = null
    private val writes = AtomicInteger(0)

    @Synchronized
    private fun dir(ctx: Context): File =
        dir ?: File(ctx.cacheDir, DIR).also {
            it.mkdirs()
            dir = it
        }

    /** חתימה של ההגדרות שמשפיעות על ההחלטה */
    private fun configKey(cfg: ImageFilterConfig) =
        "${cfg.categories.joinToString { it.name }}|${cfg.women}|${cfg.sensitivity}|${cfg.blockUnchecked}"

    private fun keyFor(cfg: ImageFilterConfig, id: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val d = md.digest((configKey(cfg) + "\n" + id).toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    fun get(ctx: Context, cfg: ImageFilterConfig, id: String): ByteArray? {
        val f = File(dir(ctx), keyFor(cfg, id))
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) {
            f.delete()
            return null
        }
        return try {
            f.readBytes().also { f.setLastModified(System.currentTimeMillis()) }
        } catch (_: Exception) {
            null
        }
    }

    fun put(ctx: Context, cfg: ImageFilterConfig, id: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        try {
            val d = dir(ctx)
            val f = File(d, keyFor(cfg, id))
            val tmp = File(d, f.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
            if (writes.incrementAndGet() % 50 == 0) trim(d)
        } catch (_: Exception) {
        }
    }

    /** תוצאה בלבד (בלי תמונה) — לתמונות מוטמעות בדף */
    fun getVerdict(ctx: Context, cfg: ImageFilterConfig, id: String): Boolean? =
        get(ctx, cfg, "verdict:$id")?.let { it.isNotEmpty() && it[0] == '1'.code.toByte() }

    fun putVerdict(ctx: Context, cfg: ImageFilterConfig, id: String, blocked: Boolean) =
        put(ctx, cfg, "verdict:$id", byteArrayOf((if (blocked) '1' else '0').code.toByte()))

    /** שומר על גודל המטמון: מוחק את הקבצים שלא השתמשו בהם הכי הרבה זמן */
    @Synchronized
    private fun trim(d: File) {
        val files = d.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES * 8 / 10) break
            total -= f.length()
            f.delete()
        }
    }

    fun clear(ctx: Context) {
        try {
            dir(ctx).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }
}
