package com.meni.whitelistfilter.provision

import android.app.Activity
import android.os.Bundle

/**
 * השלב האחרון של ההגדרה דרך QR: המערכת מבקשת מהאפליקציה לסיים את ההגדרה.
 * אין מה להגדיר כאן. את הנעילות מפעילים אחר כך מתוך האפליקציה, אחרי קביעת קוד.
 */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}
