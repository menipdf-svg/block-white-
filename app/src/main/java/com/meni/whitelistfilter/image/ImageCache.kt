package com.meni.whitelistfilter.image

import android.content.Context
import android.webkit.WebView
import com.meni.whitelistfilter.FilterState

/** מחיקת תמונות מהמטמון: גם מהזיכרון של הדפדפן וגם מההחלטות השמורות של המסנן */
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
    }
}
