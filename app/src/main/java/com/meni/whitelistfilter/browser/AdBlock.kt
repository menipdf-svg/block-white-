package com.meni.whitelistfilter.browser

/**
 * רשימת דומיינים של רשתות פרסום ומעקב. בקשה לאחד מהם (או לתת-דומיין שלו) נחסמת בדפדפן.
 * זה גם מקצר את טעינת הדפים וגם חוסך הרבה תמונות שהמודלים היו צריכים לבדוק.
 */
object AdBlock {
    private val DOMAINS = setOf(
        // גוגל — פרסום ומעקב
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "adtrafficquality.google", "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "imasdk.googleapis.com",
        // המלצות ממומנות ("כתבות שאולי יעניינו אותך")
        "taboola.com", "taboolasyndication.com", "outbrain.com", "outbrainimg.com", "zemanta.com",
        "revcontent.com", "mgid.com",
        // רשתות פרסום
        "criteo.com", "criteo.net", "adnxs.com", "adsrvr.org", "pubmatic.com", "rubiconproject.com",
        "openx.net", "casalemedia.com", "amazon-adsystem.com", "teads.tv", "smartadserver.com",
        "adform.net", "yieldmo.com", "sharethrough.com", "3lift.com", "indexww.com", "bidswitch.net",
        "adnpbs.com", "media.net", "adsafeprotected.com", "doubleverify.com", "moatads.com",
        "33across.com", "gumgum.com", "sovrn.com", "lijit.com", "contextweb.com", "spotxchange.com",
        "springserve.com", "adcolony.com", "applovin.com", "unityads.unity3d.com", "inmobi.com",
        "yieldlove.com", "seedtag.com", "connatix.com", "primis.tech", "vidazoo.com", "undertone.com",
        // מעקב ומדידה
        "scorecardresearch.com", "quantserve.com", "hotjar.com", "hotjar.io", "clarity.ms",
        "chartbeat.com", "chartbeat.net", "newrelic.com", "nr-data.net", "mixpanel.com", "segment.io",
        "branch.io", "adjust.com", "appsflyer.com", "krxd.net", "bluekai.com", "demdex.net",
        "everesttech.net", "omtrdc.net", "crwdcntrl.net", "rlcdn.com", "tapad.com", "adsymptotic.com",
        "permutive.com", "permutive.app", "id5-sync.com", "liadm.com", "agkn.com", "mathtag.com"
    )

    /** כל הרשימה — לתוסף של דפדפן ה-Firefox */
    fun all(): Set<String> = DOMAINS

    fun isAd(host: String): Boolean {
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in DOMAINS) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }
}
