package com.meni.whitelistfilter.provision

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

/**
 * אנדרואיד 10+: בהגדרה דרך QR המערכת שואלת את האפליקציה באיזה מצב לנהל את המכשיר.
 * אנחנו תמיד עונים "מכשיר מנוהל במלואו" (Device Owner).
 */
class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val allowed = intent.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES)
        if (allowed != null && DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE !in allowed) {
            setResult(RESULT_CANCELED)
        } else {
            setResult(
                RESULT_OK,
                Intent().putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
                )
            )
        }
        finish()
    }
}
