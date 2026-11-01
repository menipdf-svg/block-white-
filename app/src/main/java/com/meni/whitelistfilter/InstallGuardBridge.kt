package com.meni.whitelistfilter

import android.content.Context
import com.meni.whitelistfilter.install.InstallGuard
import com.meni.whitelistfilter.store.StoreCatalog

/** גשר קטן בין מסך החנות לבין InstallGuard, ומרענן את המסך אחרי הפעולה */
object InstallGuardBridge {
    fun sweep(ctx: Context) = InstallGuard.sweep(ctx)

    fun remove(ctx: Context, pkg: String) {
        InstallGuard.remove(ctx, pkg)
        // ההסרה רצה ברקע, והמסך מתרענן כשההסרה מסתיימת
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ StoreCatalog.bump() }, 1500)
    }
}
