package com.meni.whitelistfilter.net

import android.net.ConnectivityManager
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.Mode
import com.meni.whitelistfilter.Rules
import java.net.InetSocketAddress

/** מחבר את השרת המקומי לחוקים של האפליקציה ולזיהוי האפליקציות של אנדרואיד */
class AppPolicy(private val cm: ConnectivityManager) : ProxyPolicy {

    override fun ownerUid(protocol: Int, src: InetSocketAddress, dst: InetSocketAddress): Int = try {
        cm.getConnectionOwnerUid(protocol, src, dst)
    } catch (_: Exception) {
        -1
    }

    override fun mode(uid: Int): Mode = Rules.ruleForUid(uid).mode

    override fun isAllowed(uid: Int, domain: String): Boolean = Rules.isAllowed(uid, domain)

    override fun blocked(uid: Int, target: String, isDomain: Boolean) =
        FilterState.block(uid, target, isDomain)

    override fun passed(uid: Int, target: String, isDomain: Boolean) =
        FilterState.request(uid, target, isDomain, true)
}
