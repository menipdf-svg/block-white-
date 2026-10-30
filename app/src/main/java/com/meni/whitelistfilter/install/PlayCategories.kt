package com.meni.whitelistfilter.install

data class PlayCategory(val id: String, val title: String)

/** הקטגוריות של Google Play, כפי שהן מופיעות בדף האפליקציה (applicationCategory) */
object PlayCategories {

    /** כל אפליקציה שיודעת לפתוח אתרי אינטרנט (דפדפן), בלי קשר לקטגוריה שלה בגוגל פליי */
    const val BROWSER = "BROWSER"

    /** אפליקציה שלא קיימת ב-Google Play (למשל הותקנה מ-APK) */
    const val NOT_ON_PLAY = "NOT_ON_PLAY"

    /** מתג אחד שחוסם כל קטגוריית משחקים, גם כאלה שגוגל תוסיף בעתיד */
    const val ALL_GAMES = "ALL_GAMES"

    val special = listOf(
        PlayCategory(BROWSER, "דפדפנים"),
        PlayCategory(NOT_ON_PLAY, "לא נמצאות ב-Google Play")
    )

    val apps = listOf(
        PlayCategory("ART_AND_DESIGN", "אמנות ועיצוב"),
        PlayCategory("AUTO_AND_VEHICLES", "רכב וכלי תחבורה"),
        PlayCategory("BEAUTY", "יופי"),
        PlayCategory("BOOKS_AND_REFERENCE", "ספרים ומקורות"),
        PlayCategory("BUSINESS", "עסקים"),
        PlayCategory("COMICS", "קומיקס"),
        PlayCategory("COMMUNICATION", "תקשורת"),
        PlayCategory("DATING", "היכרויות"),
        PlayCategory("EDUCATION", "חינוך"),
        PlayCategory("ENTERTAINMENT", "בידור"),
        PlayCategory("EVENTS", "אירועים"),
        PlayCategory("FINANCE", "פיננסים"),
        PlayCategory("FOOD_AND_DRINK", "אוכל ושתייה"),
        PlayCategory("HEALTH_AND_FITNESS", "בריאות וכושר"),
        PlayCategory("HOUSE_AND_HOME", "בית וגן"),
        PlayCategory("LIBRARIES_AND_DEMO", "ספריות והדגמות"),
        PlayCategory("LIFESTYLE", "אורח חיים"),
        PlayCategory("MAPS_AND_NAVIGATION", "מפות וניווט"),
        PlayCategory("MEDICAL", "רפואה"),
        PlayCategory("MUSIC_AND_AUDIO", "מוזיקה ואודיו"),
        PlayCategory("NEWS_AND_MAGAZINES", "חדשות ומגזינים"),
        PlayCategory("PARENTING", "הורות"),
        PlayCategory("PERSONALIZATION", "התאמה אישית"),
        PlayCategory("PHOTOGRAPHY", "צילום"),
        PlayCategory("PRODUCTIVITY", "פרודוקטיביות"),
        PlayCategory("SHOPPING", "קניות"),
        PlayCategory("SOCIAL", "חברתי"),
        PlayCategory("SPORTS", "ספורט"),
        PlayCategory("TOOLS", "כלים"),
        PlayCategory("TRAVEL_AND_LOCAL", "טיולים ומקומי"),
        PlayCategory("VIDEO_PLAYERS", "נגני וידאו ועריכה"),
        PlayCategory("WEATHER", "מזג אוויר")
    )

    val games = listOf(
        PlayCategory("GAME_ACTION", "משחקי פעולה"),
        PlayCategory("GAME_ADVENTURE", "משחקי הרפתקאות"),
        PlayCategory("GAME_ARCADE", "משחקי ארקייד"),
        PlayCategory("GAME_BOARD", "משחקי לוח"),
        PlayCategory("GAME_CARD", "משחקי קלפים"),
        PlayCategory("GAME_CASINO", "משחקי קזינו"),
        PlayCategory("GAME_CASUAL", "משחקים קלילים"),
        PlayCategory("GAME_EDUCATIONAL", "משחקים חינוכיים"),
        PlayCategory("GAME_MUSIC", "משחקי מוזיקה"),
        PlayCategory("GAME_PUZZLE", "משחקי פאזלים"),
        PlayCategory("GAME_RACING", "משחקי מירוצים"),
        PlayCategory("GAME_ROLE_PLAYING", "משחקי תפקידים"),
        PlayCategory("GAME_SIMULATION", "משחקי סימולציה"),
        PlayCategory("GAME_SPORTS", "משחקי ספורט"),
        PlayCategory("GAME_STRATEGY", "משחקי אסטרטגיה"),
        PlayCategory("GAME_TRIVIA", "משחקי טריוויה"),
        PlayCategory("GAME_WORD", "משחקי מילים")
    )

    fun isGame(id: String) = id == "GAME" || id.startsWith("GAME_")

    fun title(id: String): String =
        (special + apps + games).firstOrNull { it.id == id }?.title ?: id
}
