package com.meni.whitelistfilter.image

import android.content.Context
import android.webkit.WebView
import com.meni.whitelistfilter.FilterState

/** מחיקת תמונות מהמטמון: מהזיכרון של הדפדפן, מההחלטות של המסנן, ומהמטמון בדיסק */
object ImageCache {
    /** חייב לרוץ על התהליכון הראשי */
    fun clear(ctx: Context) {
        // המטמון של WebView משותף לכל האפליקציה — מספיק לנקות דרך מופע אחד
        try {
            WebView(ctx).apply {
                clearCache(true)
                destroy()
            }
        } catch (_: Exception) {
        }
        FilterState.clearImageCache()
        // גם התמונות שנשמרו בדיסק — ברקע, כדי לא לתקוע את המסך
        val app = ctx.applicationContext
        Thread { DiskImageCache.clear(app) }.start()
    }
}
