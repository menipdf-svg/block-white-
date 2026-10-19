package com.meni.whitelistfilter.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * המודלים המקומיים. כולם רצים על המכשיר, בלי אינטרנט:
 *  - NudeNet — פנים של אישה/גבר ואזורי גוף חשופים/מכוסים (המודל הראשי)
 *  - BlazeFace — מוצא פנים (לקטגוריית "אנשים" ולגיבוי של סינון הנשים)
 *  - מודל מגדר — מקבל פנים ומחזיר אם זו אישה (גיבוי כש-NudeNet לא הכריע)
 * בנוסף, לכל אישה מחושב כמה מהגוף חשוף (לפי צבע עור), לרמה "לבוש חלקי".
 */
object ImageJudge {
    private const val TAG = "ImageJudge"
    private const val FACES = "models/blaze_face_short_range.tflite"
    private const val GENDER = "models/gender.tflite"
    private const val NUDE = "models/nudenet_320n.onnx"

    private var faces: FaceDetector? = null
    private var gender: GenderModel? = null
    private var nude: NudeModel? = null
    private var loaded = false

    @Volatile
    var error: String? = null
        private set

    /** פירוט מה המודלים ראו בתמונה האחרונה שנבדקה — ליומן התמונות */
    @Volatile
    var lastDetail: String = ""
        private set
    private val notes = ArrayList<String>()
    private fun note(s: String) { notes += s }
    private fun f2(x: Float) = String.format(java.util.Locale.US, "%.2f", x)

    @Synchronized
    private fun ensure(ctx: Context): Boolean {
        if (loaded) return nude != null || faces != null
        loaded = true
        val errors = ArrayList<String>()
        try {
            nude = NudeModel(ctx, NUDE)
        } catch (e: Throwable) {
            errors += "מודל NudeNet לא נטען: ${e.message}"
            Log.e(TAG, "nude", e)
        }
        try {
            faces = FaceDetector.createFromOptions(
                ctx,
                FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(FACES).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinDetectionConfidence(0.45f)
                    .build()
            )
        } catch (e: Throwable) {
            errors += "מודל זיהוי הפנים לא נטען: ${e.message}"
            Log.e(TAG, "faces", e)
        }
        try {
            gender = GenderModel(ctx, GENDER)
        } catch (e: Throwable) {
            errors += "מודל המגדר לא נטען: ${e.message}"
            Log.e(TAG, "gender", e)
        }
        error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
        return nude != null || faces != null
    }

    /**
     * מחזיר את הסיבות לחסימה (ריק = התמונה בסדר).
     * זורק חריגה אם המודלים לא זמינים — כדי שהקורא יחליט מה לעשות בתמונה שלא נבדקה.
     */
    @Synchronized
    fun find(ctx: Context, bitmap: Bitmap, cfg: ImageFilterConfig): Set<String> {
        val wantPeople = ImageCategory.PEOPLE in cfg.categories
        val women = cfg.women
        if (!wantPeople && women == WomenLevel.OFF) return emptySet()
        if (!ensure(ctx)) throw IllegalStateException(error ?: "המודל לא זמין")
        notes.clear()
        lastDetail = ""
        val started = System.currentTimeMillis()

        val bmp = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val reasons = HashSet<String>()

        try {
            // NudeNet רץ פעם אחת, והתוצאה משמשת גם לאנשים וגם לנשים
            val nudeThr = nudeThreshold(cfg.sensitivity)
            val found: List<NudeDetection> = nude?.let { model ->
                try {
                    model.detect(bmp, minOf(0.2f, nudeThr))
                } catch (e: Throwable) {
                    Log.w(TAG, "nudenet: ${e.message}")
                    null
                }
            } ?: emptyList()
            if (found.isNotEmpty()) {
                note("NudeNet: " + found.sortedByDescending { it.score }.take(5).joinToString(", ") { "${it.label} ${f2(it.score)}" })
            }

            // 1) אנשים — פנים לפי NudeNet, או פנים לפי מודל הפנים
            if (wantPeople) {
                val nudeFace = found.any { (it.label == "FACE_FEMALE" || it.label == "FACE_MALE") && it.score >= nudeThr }
                if (nudeFace) {
                    reasons += "PEOPLE"
                } else {
                    val n = findFaces(bmp, Rect(0, 0, bmp.width, bmp.height)).size
                    if (n > 0) {
                        note("פנים בתמונה: $n")
                        reasons += "PEOPLE"
                    }
                }
            }

            // 2) נשים — קודם NudeNet, ורק אם לא הכריע — פנים + מודל מגדר + אחוז עור
            if (women != WomenLevel.OFF && reasons.isEmpty()) {
                val womanFallback = { checkWomen(bmp, emptyList(), cfg.copy(women = WomenLevel.ALL), 0f, 0f, 0f) }
                if (nudeVerdict(found, cfg, womanFallback)) {
                    reasons += "WOMEN_NUDENET"
                } else if (checkWomen(bmp, emptyList(), cfg, 0f, 0f, 0f)) {
                    reasons += "WOMEN"
                }
            }
        } finally {
            if (bmp !== bitmap) bmp.recycle()
            note("זמן: ${System.currentTimeMillis() - started}ms")
            lastDetail = notes.joinToString(" · ")
        }
        return reasons
    }

    // =====================================================================
    //  נשים
    // =====================================================================

    /** כמה בטוח מודל המגדר צריך להיות, לפי הרגישות */
    private fun genderThreshold(s: Sensitivity) = when (s) {
        Sensitivity.LOW -> 0.6f
        Sensitivity.MEDIUM -> 0.45f
        Sensitivity.HIGH -> 0.3f
    }

    /** איזה חלק מהגוף (בלי הפנים) צריך להיות חשוף כדי לטשטש */
    private fun exposureThreshold(level: WomenLevel, s: Sensitivity): Float = when (level) {
        WomenLevel.SWIMWEAR -> when (s) {
            Sensitivity.LOW -> 0.42f
            Sensitivity.MEDIUM -> 0.32f
            Sensitivity.HIGH -> 0.24f
        }
        WomenLevel.PARTIAL -> when (s) {
            Sensitivity.LOW -> 0.18f
            Sensitivity.MEDIUM -> 0.12f
            Sensitivity.HIGH -> 0.08f
        }
        else -> 0f
    }

    // ----- NudeNet -----

    private val NUDE_EXPLICIT = setOf(
        "FEMALE_BREAST_EXPOSED", "FEMALE_GENITALIA_EXPOSED", "BUTTOCKS_EXPOSED",
        "ANUS_EXPOSED", "MALE_GENITALIA_EXPOSED"
    )

    /** תוויות שמעידות בעצמן על אישה בלבוש של בגד ים / תחתון */
    private val NUDE_FEMALE_SWIMWEAR = setOf("FEMALE_BREAST_COVERED", "FEMALE_GENITALIA_COVERED", "BUTTOCKS_COVERED")

    private fun nudeThreshold(s: Sensitivity) = when (s) {
        Sensitivity.LOW -> 0.5f
        Sensitivity.MEDIUM -> 0.35f
        Sensitivity.HIGH -> 0.25f
    }

    /**
     * ההחלטה לפי NudeNet. [womanSeenFallback] נקרא רק כשצריך לדעת אם יש אישה בתמונה
     * ו-NudeNet לא ראה פנים של אישה (למשל בטן חשופה בלי פנים).
     */
    private fun nudeVerdict(found: List<NudeDetection>, cfg: ImageFilterConfig, womanSeenFallback: () -> Boolean): Boolean {
        val thr = nudeThreshold(cfg.sensitivity)
        fun has(label: String) = found.any { it.label == label && it.score >= thr }
        fun hasAny(labels: Set<String>) = labels.any { has(it) }

        // תוכן מפורש — נחסם בכל רמה
        if (hasAny(NUDE_EXPLICIT)) return true

        val femaleFace = has("FACE_FEMALE")
        val femaleSwimwear = hasAny(NUDE_FEMALE_SWIMWEAR)
        val belly = has("BELLY_EXPOSED")
        val armpits = has("ARMPITS_EXPOSED")

        // האם יש כאן אישה — לפי NudeNet, ואם לא בטוח לפי שאר המודלים
        val womanSeen by lazy { femaleFace || femaleSwimwear || womanSeenFallback() }

        return when (cfg.women) {
            WomenLevel.ALL -> femaleFace || femaleSwimwear
            WomenLevel.SWIMWEAR -> femaleSwimwear || (belly && womanSeen)
            WomenLevel.PARTIAL -> femaleSwimwear || ((belly || armpits) && womanSeen)
            WomenLevel.OFF -> false
        }
    }

    private fun checkWomen(
        bmp: Bitmap,
        personBoxes: List<RectF>,
        cfg: ImageFilterConfig,
        femaleHints: Float,
        swimwearLabels: Float,
        revealingLabels: Float
    ): Boolean {
        val level = cfg.women
        val s = cfg.sensitivity
        val genderThr = genderThreshold(s)
        val labelThr = s.threshold

        // כל האנשים שזוהו, ועוד פנים שנמצאו ישירות בתמונה ולא שייכות לאף אדם שזוהה
        // (תקריב, אדם חתוך, או אדם שמודל הזיהוי פספס)
        val people = ArrayList<Pair<Rect, Rect?>>() // (אדם, פנים שכבר נמצאו)
        personBoxes.forEach { people += clampRect(it, bmp) to null }
        for (face in findFaces(bmp, Rect(0, 0, bmp.width, bmp.height))) {
            val inside = people.any { (p, _) -> p.contains(face.centerX(), face.centerY()) }
            if (!inside) {
                // מגדילים את הפנים לגוף משוער: פי 3 ברוחב, פי 7 לגובה כלפי מטה
                val w = face.width()
                val body = clampRect(
                    RectF(
                        (face.centerX() - w * 1.5f), face.top.toFloat(),
                        (face.centerX() + w * 1.5f), (face.top + face.height() * 7f)
                    ),
                    bmp
                )
                people += body to face
            }
        }

        // אין אדם ואין פנים — אבל יש בגדים נשיים בתמונה (למשל צילום מהגב, או מוצר על דוגמנית)
        if (people.isEmpty()) {
            if (femaleHints < labelThr) return false
            return when (level) {
                WomenLevel.ALL -> true
                WomenLevel.SWIMWEAR -> swimwearLabels >= labelThr
                WomenLevel.PARTIAL -> revealingLabels >= labelThr
                WomenLevel.OFF -> false
            }
        }

        for ((personRect, knownFace) in people) {
            if (personRect.width() < 20 || personRect.height() < 20) continue

            val face = knownFace ?: run {
                // מחפשים פנים בחלק העליון של האדם (שם הראש), כדי שהפנים יהיו גדולות מספיק למודל
                val headH = min(personRect.height(), (personRect.width() * 1.3f).roundToInt())
                findFaces(bmp, Rect(personRect.left, personRect.top, personRect.right, personRect.top + headH)).firstOrNull()
            }

            val femaleProb: Float? = face?.let { f -> genderOf(bmp, f) }
            note(if (femaleProb != null) "מגדר: אישה ${f2(femaleProb)}" else "מגדר: לא נמצאו פנים")
            val isWoman = when {
                femaleProb != null -> femaleProb >= genderThr
                // לא רואים פנים (גב, צד, רחוק). ברגישות גבוהה — מניחים שזו אישה, ליתר ביטחון
                s == Sensitivity.HIGH -> true
                // אחרת — לפי בגדים שמעידים על אישה (בסף נמוך יותר)
                else -> femaleHints >= labelThr * 0.6f
            }
            if (!isWoman) continue

            when (level) {
                WomenLevel.ALL -> return true
                WomenLevel.SWIMWEAR -> {
                    if (swimwearLabels >= labelThr * 0.7f) return true
                    val skin = skinRatio(bmp, personRect, face)
                    note("עור חשוף: ${f2(skin)}")
                    if (skin >= exposureThreshold(level, s)) return true
                }
                WomenLevel.PARTIAL -> {
                    if (revealingLabels >= labelThr * 0.7f) return true
                    val skin = skinRatio(bmp, personRect, face)
                    note("עור חשוף: ${f2(skin)}")
                    if (skin >= exposureThreshold(level, s)) return true
                }
                WomenLevel.OFF -> return false
            }
        }
        return false
    }

    /** מחפש פנים בתוך האזור, מהגדולות לקטנות. מחזיר מלבנים בקואורדינטות של כל התמונה */
    private fun findFaces(bmp: Bitmap, area: Rect): List<Rect> {
        val fd = faces ?: return emptyList()
        if (area.width() < 16 || area.height() < 16) return emptyList()
        val crop = Bitmap.createBitmap(bmp, area.left, area.top, area.width(), area.height())
        try {
            val result = fd.detect(BitmapImageBuilder(crop).build())
            return result.detections()
                .sortedByDescending { it.boundingBox().width() * it.boundingBox().height() }
                .take(6)
                .map { d ->
                    val b = d.boundingBox()
                    // מרווח סביב הפנים — מודל המגדר אומן על פנים עם קצת שיער ורקע
                    val mx = b.width() * 0.25f
                    val my = b.height() * 0.25f
                    clampRect(
                        RectF(
                            area.left + b.left - mx, area.top + b.top - my * 1.4f,
                            area.left + b.right + mx, area.top + b.bottom + my * 0.6f
                        ),
                        bmp
                    )
                }
        } catch (e: Throwable) {
            Log.w(TAG, "face: ${e.message}")
            return emptyList()
        } finally {
            if (crop !== bmp) crop.recycle()
        }
    }

    private fun genderOf(bmp: Bitmap, face: Rect): Float? {
        val gm = gender ?: return null
        if (face.width() < 12 || face.height() < 12) return null
        val crop = Bitmap.createBitmap(bmp, face.left, face.top, face.width(), face.height())
        return try {
            gm.femaleProbability(crop)
        } catch (e: Throwable) {
            Log.w(TAG, "gender: ${e.message}")
            null
        } finally {
            if (crop !== bmp) crop.recycle()
        }
    }

    /**
     * איזה חלק מהאדם (בלי אזור הפנים) הוא בצבע עור.
     * לבוש צנוע ≈ כמעט רק ידיים; חולצה קצרה ≈ 10–20%; בגד ים ≈ 30% ומעלה.
     */
    private fun skinRatio(bmp: Bitmap, person: Rect, face: Rect?): Float {
        val maxSide = 96
        val scale = min(1f, maxSide.toFloat() / max(person.width(), person.height()))
        val w = max(1, (person.width() * scale).roundToInt())
        val h = max(1, (person.height() * scale).roundToInt())
        val crop = Bitmap.createBitmap(bmp, person.left, person.top, person.width(), person.height())
        val small = Bitmap.createScaledBitmap(crop, w, h, true)
        if (crop !== bmp && crop !== small) crop.recycle()
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()

        // אזור הפנים בקואורדינטות של התמונה המוקטנת
        val fx1 = face?.let { ((it.left - person.left) * scale).toInt() } ?: -1
        val fy1 = face?.let { ((it.top - person.top) * scale).toInt() } ?: -1
        val fx2 = face?.let { ((it.right - person.left) * scale).toInt() } ?: -1
        val fy2 = face?.let { ((it.bottom - person.top) * scale).toInt() } ?: -1

        var skin = 0
        var total = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x in fx1..fx2 && y in fy1..fy2) continue
                total++
                if (isSkin(px[y * w + x])) skin++
            }
        }
        return if (total == 0) 0f else skin.toFloat() / total
    }

    /** זיהוי עור לפי צבע (YCbCr + RGB) — עובד על מגוון גווני עור */
    private fun isSkin(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        if (y < 45f) return false
        if (cr !in 135f..180f || cb !in 80f..135f) return false
        return r > g && r > b && (r - min(g, b)) > 12
    }

    private fun clampRect(r: RectF, bmp: Bitmap): Rect {
        val l = r.left.roundToInt().coerceIn(0, bmp.width - 1)
        val t = r.top.roundToInt().coerceIn(0, bmp.height - 1)
        val rr = r.right.roundToInt().coerceIn(l + 1, bmp.width)
        val bb = r.bottom.roundToInt().coerceIn(t + 1, bmp.height)
        return Rect(l, t, rr, bb)
    }
}
