package com.meni.whitelistfilter.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.meni.whitelistfilter.FilterState
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * בודק תמונה אחת במודלים ומחזיר אם לחסום אותה.
 * את המשטח האפור במקום התמונה מצייר פיירפוקס עצמו — כאן רק מחליטים.
 */
class ImageChecker(context: Context) {
    private val ctx = context.applicationContext

    companion object {
        private const val TAG = "ImageChecker"
        /** תמונות קטנות מזה הן אייקונים וכפתורים — לא בודקים */
        private const val MIN_SIDE = 48
        /** גודל התמונה שנותנים למודל */
        private const val ANALYZE_SIDE = 640
    }

    /**
     * true = לחסום.
     * תמונה שכבר נבדקה (גם לפני הפעלה מחדש של הטלפון) לא נבדקת שוב — הזיהוי לפי התוכן שלה.
     * [url] משמש רק ליומן.
     */
    fun isBlocked(bytes: ByteArray, url: String): Boolean {
        val cfg = ImageFilterSettings.config.value
        if (!cfg.active) return false
        // מזהים תמונה לפי התוכן שלה, לא לפי הכתובת: אתרים כמו אינסטגרם משנים את הכתובת בכל טעינה
        val id = "img:" + sha1(bytes)
        DiskImageCache.getVerdict(ctx, cfg, id)?.let { return it }

        // אותה תמונה כבר בבדיקה (הדף ביקש אותה פעמיים) — מחכים לתוצאה במקום לבדוק שוב
        val mine = Pending()
        val running = inFlight.putIfAbsent(id, mine)
        if (running != null) {
            running.done.await(60, TimeUnit.SECONDS)
            return running.blocked ?: cfg.blockUnchecked
        }
        try {
            val blocked = judge(bytes, cfg, url)
            DiskImageCache.putVerdict(ctx, cfg, id, blocked)
            FilterState.imageChecked(blocked)
            mine.blocked = blocked
            return blocked
        } finally {
            inFlight.remove(id)
            mine.done.countDown()
        }
    }

    private class Pending {
        @Volatile var blocked: Boolean? = null
        val done = CountDownLatch(1)
    }

    private val inFlight = ConcurrentHashMap<String, Pending>()

    private fun judge(bytes: ByteArray, cfg: ImageFilterConfig, url: String): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight

        if (w <= 0 || h <= 0) {
            FilterState.logImage(url, cfg.blockUnchecked, "פורמט לא נתמך", "${bytes.size} bytes")
            return cfg.blockUnchecked
        }
        if (w < MIN_SIDE || h < MIN_SIDE) return false

        var sample = 1
        while (max(w, h) / (sample * 2) >= ANALYZE_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
        if (bmp == null) {
            FilterState.logImage(url, cfg.blockUnchecked, "פענוח נכשל", "${w}x$h")
            return cfg.blockUnchecked
        }

        try {
            val found = try {
                ImageJudge.find(ctx, bmp, cfg)
            } catch (e: Exception) {
                Log.w(TAG, "judge: ${e.message}")
                FilterState.logImage(url, cfg.blockUnchecked, "שגיאה בבדיקה", e.message ?: "")
                return cfg.blockUnchecked
            }
            val detail = "${w}x$h · " + ImageJudge.lastDetail
            FilterState.logImage(url, found.isNotEmpty(), found.joinToString(", ") { reasonTitle(it) }.ifEmpty { "תקין" }, detail)
            return found.isNotEmpty()
        } finally {
            bmp.recycle()
        }
    }

    private fun reasonTitle(r: String) = when (r) {
        "PEOPLE" -> "אנשים"
        "WOMEN_NUDENET" -> "אישה (NudeNet)"
        "WOMEN" -> "אישה"
        else -> r
    }

    private fun sha1(b: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
}
