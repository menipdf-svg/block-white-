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
 * המודלים המקומיים, במפל של שני שלבים:
 *  1. NudeNet 320n (קטן ומהיר) רץ על כל תמונה ונותן סבירות שיש בה אישה.
 *     אם הסבירות נמוכה מהשער — התמונה עוברת מיד.
 *  2. NudeNet 640m (גדול ומדויק) רץ רק על התמונות החשודות, ומחליט.
 * בנוסף: BlazeFace לקטגוריית "אנשים", ואחוז עור חשוף לרמה "לבוש חלקי".
 */
object ImageJudge {
    private const val TAG = "ImageJudge"
    private const val FACES = "models/blaze_face_short_range.tflite"
    private const val NUDE_LIGHT = "models/nudenet_320n.onnx"
    private const val NUDE_HEAVY = "models/nudenet_640m.onnx"

    private var faces: FaceDetector? = null
    private var light: NudeModel? = null
    private var heavy: NudeModel? = null
    private var loaded = false
    private var heavyLoaded = false

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

    // ----- תוויות NudeNet -----

    private val EXPLICIT = setOf(
        "FEMALE_BREAST_EXPOSED", "FEMALE_GENITALIA_EXPOSED", "BUTTOCKS_EXPOSED",
        "ANUS_EXPOSED", "MALE_GENITALIA_EXPOSED"
    )

    /** תוויות שמעידות בעצמן על אישה בבגד ים / תחתון */
    private val FEMALE_SWIMWEAR = setOf("FEMALE_BREAST_COVERED", "FEMALE_GENITALIA_COVERED", "BUTTOCKS_COVERED")

    /** כל מה שמרמז שיש אישה בתמונה — לשער של המודל הקטן */
    private val FEMALE_SIGNALS = setOf("FACE_FEMALE") + FEMALE_SWIMWEAR + EXPLICIT

    /** שער המודל הקטן: מעל זה התמונה נשלחת למודל הגדול */
    private fun gate(s: Sensitivity) = when (s) {
        Sensitivity.LOW -> 0.6f
        Sensitivity.MEDIUM -> 0.5f
        Sensitivity.HIGH -> 0.3f
    }

    /** הסף של המודל הגדול להחלטה הסופית */
    private fun decide(s: Sensitivity) = when (s) {
        Sensitivity.LOW -> 0.55f
        Sensitivity.MEDIUM -> 0.45f
        Sensitivity.HIGH -> 0.35f
    }

    /** איזה חלק מהגוף (בלי הפנים) צריך להיות חשוף כדי לחסום ברמה "לבוש חלקי" */
    private fun partialSkin(s: Sensitivity) = when (s) {
        Sensitivity.LOW -> 0.18f
        Sensitivity.MEDIUM -> 0.13f
        Sensitivity.HIGH -> 0.09f
    }

    @Synchronized
    private fun ensure(ctx: Context): Boolean {
        if (loaded) return light != null || faces != null
        loaded = true
        val errors = ArrayList<String>()
        try {
            light = NudeModel(ctx, NUDE_LIGHT, 320)
        } catch (e: Throwable) {
            errors += "NudeNet 320n לא נטען: ${e.message}"
            Log.e(TAG, "light", e)
        }
        try {
            faces = FaceDetector.createFromOptions(
                ctx,
                FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(FACES).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinDetectionConfidence(0.5f)
                    .build()
            )
        } catch (e: Throwable) {
            errors += "מודל הפנים לא נטען: ${e.message}"
            Log.e(TAG, "faces", e)
        }
        error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
        return light != null || faces != null
    }

    /** המודל הגדול נטען רק בפעם הראשונה שצריך אותו */
    @Synchronized
    private fun heavyModel(ctx: Context): NudeModel? {
        if (heavyLoaded) return heavy
        heavyLoaded = true
        try {
            heavy = NudeModel(ctx, NUDE_HEAVY, 640)
        } catch (e: Throwable) {
            error = (error?.let { "$it\n" } ?: "") + "NudeNet 640m לא נטען: ${e.message}"
            Log.e(TAG, "heavy", e)
        }
        return heavy
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
        val s = cfg.sensitivity

        try {
            // שלב 1: המודל הקטן
            val quick: List<NudeDetection> = light?.let { m ->
                try {
                    m.detect(bmp, 0.1f)
                } catch (e: Throwable) {
                    Log.w(TAG, "320n: ${e.message}")
                    null
                }
            } ?: emptyList()
            if (quick.isNotEmpty()) {
                note("320n: " + quick.sortedByDescending { it.score }.take(4).joinToString(", ") { "${it.label} ${f2(it.score)}" })
            }

            // אנשים — כל תמונה עם פנים (לפי NudeNet או לפי מודל הפנים)
            if (wantPeople) {
                val face = quick.any { (it.label == "FACE_FEMALE" || it.label == "FACE_MALE") && it.score >= gate(s) }
                if (face || findFaces(bmp, Rect(0, 0, bmp.width, bmp.height)).isNotEmpty()) {
                    reasons += "PEOPLE"
                    return reasons
                }
            }

            if (women == WomenLevel.OFF) return reasons

            // השער: מה הסבירות שיש כאן אישה (או תוכן מפורש)
            val chance = quick.filter { it.label in FEMALE_SIGNALS }.maxOfOrNull { it.score } ?: 0f
            note("סבירות לאישה: ${f2(chance)}")
            if (chance < gate(s)) return reasons

            // שלב 2: המודל הגדול מחליט
            val sure: List<NudeDetection> = heavyModel(ctx)?.let { m ->
                try {
                    m.detect(bmp, 0.2f)
                } catch (e: Throwable) {
                    Log.w(TAG, "640m: ${e.message}")
                    null
                }
            } ?: run {
                note("640m לא זמין — מחליט לפי 320n")
                quick
            }
            if (sure !== quick && sure.isNotEmpty()) {
                note("640m: " + sure.sortedByDescending { it.score }.take(4).joinToString(", ") { "${it.label} ${f2(it.score)}" })
            }

            if (womanVerdict(bmp, sure, cfg)) reasons += "WOMEN_NUDENET"
        } finally {
            if (bmp !== bitmap) bmp.recycle()
            note("זמן: ${System.currentTimeMillis() - started}ms")
            lastDetail = notes.joinToString(" · ")
        }
        return reasons
    }

    /** ההחלטה הסופית לפי המודל הגדול */
    private fun womanVerdict(bmp: Bitmap, found: List<NudeDetection>, cfg: ImageFilterConfig): Boolean {
        val thr = decide(cfg.sensitivity)
        fun best(label: String) = found.filter { it.label == label && it.score >= thr }.maxByOrNull { it.score }
        fun hasAny(labels: Set<String>) = labels.any { best(it) != null }

        // תוכן מפורש — נחסם בכל רמה
        if (hasAny(EXPLICIT)) return true

        val femaleFace = best("FACE_FEMALE")
        val swimwear = hasAny(FEMALE_SWIMWEAR)
        val womanSeen = femaleFace != null || swimwear
        if (!womanSeen) return false

        return when (cfg.women) {
            WomenLevel.ALL -> true
            WomenLevel.SWIMWEAR -> swimwear || best("BELLY_EXPOSED") != null
            WomenLevel.PARTIAL -> {
                if (swimwear || best("BELLY_EXPOSED") != null || best("ARMPITS_EXPOSED") != null) return true
                // זרועות/רגליים חשופות — NudeNet לא מזהה, אז בודקים כמה עור יש בגוף שמתחת לפנים
                val face = femaleFace?.box ?: return false
                val w = face.width()
                val body = clampRect(
                    RectF(face.centerX() - w * 1.6f, face.top, face.centerX() + w * 1.6f, face.top + face.height() * 7f),
                    bmp
                )
                val skin = skinRatio(bmp, body, clampRect(face, bmp))
                note("עור חשוף: ${f2(skin)}")
                skin >= partialSkin(cfg.sensitivity)
            }
            WomenLevel.OFF -> false
        }
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
