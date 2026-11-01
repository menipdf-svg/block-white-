package com.meni.whitelistfilter.massinstall

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.meni.whitelistfilter.MainActivity

/**
 * שירות נגישות להתקנה המונית מגוגל פליי.
 * מוגבל בהגדרות לחבילה com.android.vending בלבד, ולכן לא רואה אף אפליקציה אחרת.
 * הוא פועל רק בזמן שהתקנה המונית רצה (PlayBridge.target לא ריק).
 */
class MassInstallService : AccessibilityService() {

    companion object {
        const val PLAY_PACKAGE = "com.android.vending"
        private val LABELS = listOf("Install", "התקנה", "התקן")

        @Volatile
        var instance: MassInstallService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** פותח את דף האפליקציה בגוגל פליי */
    fun openPlay(pkg: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
            .setPackage(PLAY_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    /** בסיום מחזיר את האפליקציה שלנו לקדמה, כדי לראות את התוצאות */
    fun returnToApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (PlayBridge.target == null || PlayBridge.clicked) return
        if (event?.packageName?.toString() != PLAY_PACKAGE) return
        PlayBridge.seenPlay = true
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != PLAY_PACKAGE) return
        if (pressInstall(root)) PlayBridge.onClicked()
    }

    /**
     * הכפתור הראשי של דף האפליקציה הוא הכפתור "התקנה" הגבוה ביותר במסך.
     * בדפים של אפליקציה בתשלום או כזו שכבר מותקנת, יש "התקנה" רק בכרטיסי אפליקציות דומות
     * למטה, ולכן לא לוחצים על כפתור שנמצא בחלק התחתון של המסך.
     */
    private fun pressInstall(root: AccessibilityNodeInfo): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels
        val rect = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE
        for (label in LABELS) {
            for (node in root.findAccessibilityNodeInfosByText(label)) {
                val text = (node.text ?: node.contentDescription)?.toString()?.trim()
                if (text != label || !node.isVisibleToUser || !node.isEnabled) continue
                val target = clickableAncestor(node) ?: continue
                target.getBoundsInScreen(rect)
                if (rect.top < bestTop) {
                    bestTop = rect.top
                    best = target
                }
            }
        }
        val button = best ?: return false
        if (bestTop > screenHeight * 0.55) return false
        return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    override fun onInterrupt() {}
}
