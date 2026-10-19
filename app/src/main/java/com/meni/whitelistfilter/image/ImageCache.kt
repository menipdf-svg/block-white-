package com.meni.whitelistfilter.image

import android.content.Context
import com.meni.whitelistfilter.FilterState

/** מחיקת ההחלטות השמורות על תמונות — כל תמונה תיבדק מחדש בפעם הבאה שתופיע */
object ImageCache {
    fun clear(ctx: Context) {
        FilterState.clearImageCache()
        val app = ctx.applicationContext
        Thread { DiskImageCache.clear(app) }.start()
    }
}
