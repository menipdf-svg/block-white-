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
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * המודלים המקומיים. כולם רצים על המכשיר, בלי אינטרנט:
 *  - EfficientDet-Lite2 — מוצא אובייקטים בתמונה (אדם, רכב, בעלי חיים...)
 *  - EfficientNet-Lite0 — מסווג את כל התמונה (נוף, בגד ים, שמלה, סוג רכב...)
 *  - BlazeFace — מוצא פנים
 *  - מודל מגדר — מקבל פנים ומחזיר אם זו אישה
 *  - NudeNet — פנים של אישה ואזורי גוף חשופים/מכוסים (הכי מדויק לסינון נשים)
 * בנוסף, לכל אישה מחושב כמה מהגוף חשוף (לפי צבע עור), לרמות "בגד ים" ו"לבוש חלקי".
 */
object ImageJudge {
    private const val TAG = "ImageJudge"
    private const val DETECTOR = "models/efficientdet_lite2.tflite"
    private const val CLASSIFIER = "models/efficientnet_lite0.tflite"
    private const val FACES = "models/blaze_face_short_range.tflite"
    private const val GENDER = "models/gender.tflite"
    private const val NUDE = "models/nudenet_320n.onnx"

    private var detector: ObjectDetector? = null
    private var classifier: ImageClassifier? = null
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

    // ----- תוויות של מודל הזיהוי (COCO) -----
    private val DET_PEOPLE = setOf("person")
    private val DET_VEHICLES = setOf("car", "bus", "truck", "motorcycle", "bicycle", "train", "airplane", "boat")
    private val DET_ANIMALS = setOf(
        "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe"
    )

    // ----- תוויות של מודל הסיווג (ImageNet) -----
    private val CLS_LANDSCAPES = setOf(
        "alp", "cliff", "coral reef", "geyser", "lakeside", "promontory", "sandbar",
        "seashore", "valley", "volcano", "breakwater", "dam", "boathouse", "lakeshore"
    )
    private val CLS_VEHICLES = setOf(
        "sports car", "convertible", "cab", "jeep", "limousine", "minivan", "pickup", "police van",
        "racer", "beach wagon", "model t", "ambulance", "school bus", "trolleybus", "minibus",
        "moving van", "trailer truck", "tow truck", "garbage truck", "fire engine", "tractor",
        "motor scooter", "moped", "mountain bike", "bicycle-built-for-two", "tricycle", "go-kart",
        "golfcart", "passenger car", "streetcar", "electric locomotive", "steam locomotive",
        "bullet train", "airliner", "warplane", "speedboat", "container ship", "car wheel", "grille",
        "half track", "snowmobile", "forklift", "recreational vehicle", "amphibian", "jinrikisha",
        "horse cart", "oxcart", "freight car", "liner", "fireboat", "lifeboat", "catamaran", "trimaran",
        "yawl", "schooner", "submarine", "airship", "space shuttle", "car mirror", "disk brake"
    )

    /** בגדים ודברים שמופיעים כמעט תמיד על אדם — סימן נוסף לתמונה של אנשים */
    private val CLS_PEOPLE = setOf(
        "groom", "scuba diver", "ballplayer", "suit", "jersey", "miniskirt", "bikini", "maillot",
        "swimming trunks", "sweatshirt", "cardigan", "abaya", "gown", "kimono", "lab coat",
        "military uniform", "bow tie", "wig", "lipstick", "brassiere", "fur coat", "trench coat",
        "academic gown", "sarong", "poncho", "overskirt", "hoopskirt", "jean", "pajama", "windsor tie",
        "mortarboard", "bonnet", "cowboy hat", "sombrero", "stole", "vestment", "neck brace"
    )

    /** בגדי ים */
    private val CLS_SWIMWEAR = setOf("bikini", "maillot", "swimming trunks", "brassiere")

    /** לבוש חשוף חלקית */
    private val CLS_REVEALING = CLS_SWIMWEAR + setOf("miniskirt", "sarong")

    /** סימנים שבדרך כלל מעידים על אישה בתמונה (כשלא רואים פנים) */
    private val CLS_FEMALE_HINTS = setOf(
        "bikini", "maillot", "brassiere", "miniskirt", "gown", "hoopskirt", "overskirt",
        "lipstick", "wig", "abaya", "kimono", "sarong", "bonnet", "stole"
    )

    /** במודל ImageNet, המחלקות הראשונות (עד 397 בערך) הן בעלי חיים */
    private const val LAST_ANIMAL_INDEX = 398

    @Synchronized
    private fun ensure(ctx: Context): Boolean {
        if (loaded) return detector != null || classifier != null
        loaded = true
        val errors = ArrayList<String>()
        try {
            detector = ObjectDetector.createFromOptions(
                ctx,
                ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(DETECTOR).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(25)
                    .setScoreThreshold(0.15f)
                    .build()
            )
        } catch (e: Throwable) {
            errors += "מודל הזיהוי לא נטען: ${e.message}"
            Log.e(TAG, "detector", e)
        }
        try {
            classifier = ImageClassifier.createFromOptions(
                ctx,
                ImageClassifier.ImageClassifierOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(CLASSIFIER).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(10)
                    .setScoreThreshold(0.03f)
                    .build()
            )
        } catch (e: Throwable) {
            errors += "מודל הסיווג לא נטען: ${e.message}"
            Log.e(TAG, "classifier", e)
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
        try {
            nude = NudeModel(ctx, NUDE)
        } catch (e: Throwable) {
            errors += "מודל NudeNet לא נטען: ${e.message}"
            Log.e(TAG, "nude", e)
        }
        error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
        return detector != null || classifier != null
    }

    /**
     * מחזיר את הסיבות לטשטוש (ריק = התמונה בסדר).
     * זורק חריגה אם המודלים לא זמינים — כדי שהקורא יחליט מה לעשות בתמונה שלא נבדקה.
     */
    @Synchronized
    fun find(ctx: Context, bitmap: Bitmap, cfg: ImageFilterConfig): Set<String> {
        val wanted = cfg.categories
        val women = cfg.women
        if (wanted.isEmpty() && women == WomenLevel.OFF) return emptySet()
        if (!ensure(ctx)) throw IllegalStateException(error ?: "המודל לא זמין")
        notes.clear()
        lastDetail = ""

        val threshold = cfg.sensitivity.threshold
        val bmp = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val image = BitmapImageBuilder(bmp).build()
        val reasons = HashSet<String>()
        val imageArea = (bmp.width * bmp.height).toFloat()

        try {
            // 1) סיווג כל התמונה. סוכמים את הציונים לכל סוג, כי המודל מתפזר בין תוויות דומות
            val labels = HashMap<String, Float>()
            val cls = classifier
            if (cls != null) {
                val sums = HashMap<ImageCategory, Float>()
                cls.classify(image).classificationResult().classifications().firstOrNull()?.categories()?.forEach { c ->
                    val name = c.categoryName().lowercase()
                    labels[name] = c.score()
                    val cat = when {
                        name in CLS_LANDSCAPES -> ImageCategory.LANDSCAPES
                        name in CLS_VEHICLES -> ImageCategory.VEHICLES
                        name in CLS_PEOPLE -> ImageCategory.PEOPLE
                        c.index() in 1..LAST_ANIMAL_INDEX -> ImageCategory.ANIMALS
                        else -> null
                    }
                    if (cat != null) sums[cat] = (sums[cat] ?: 0f) + c.score()
                }
                labels.entries.sortedByDescending { it.value }.take(3).takeIf { it.isNotEmpty() }?.let { top ->
                    note("סיווג: " + top.joinToString(", ") { "${it.key} ${f2(it.value)}" })
                }
                sums.forEach { (cat, score) -> if (score >= threshold && cat in wanted) reasons += cat.name }
            }
            fun labelScore(set: Set<String>) = labels.filterKeys { it in set }.values.sum()

            // 2) זיהוי אובייקטים
            val persons = ArrayList<RectF>()
            val det = detector
            if (det != null) {
                for (d in det.detect(image).detections()) {
                    val box = d.boundingBox()
                    // אובייקט זעיר בפינה של התמונה לא נחשב
                    if (box.width() * box.height() < imageArea * 0.005f) continue
                    for (c in d.categories()) {
                        if (c.score() >= 0.2f) note("זוהה: ${c.categoryName()} ${f2(c.score())}")
                        when (c.categoryName().lowercase()) {
                            in DET_PEOPLE -> {
                                if (c.score() >= min(0.3f, threshold)) persons += box
                                if (c.score() >= threshold && ImageCategory.PEOPLE in wanted) reasons += "PEOPLE"
                            }
                            in DET_VEHICLES -> if (c.score() >= threshold && ImageCategory.VEHICLES in wanted) reasons += "VEHICLES"
                            in DET_ANIMALS -> if (c.score() >= threshold && ImageCategory.ANIMALS in wanted) reasons += "ANIMALS"
                        }
                    }
                }
            }

            // 2ב) אנשים — גם פנים שנמצאו ישירות (תקריב שמודל הזיהוי לא סימן כאדם)
            if (ImageCategory.PEOPLE in wanted && "PEOPLE" !in reasons) {
                val n = findFaces(bmp, Rect(0, 0, bmp.width, bmp.height)).size
                if (n > 0) {
                    note("פנים בתמונה: $n")
                    reasons += "PEOPLE"
                }
            }

            // 3) נשים — רק אם עוד לא הוחלט לטשטש
            if (women != WomenLevel.OFF && reasons.isEmpty()) {
                val hints = labelScore(CLS_FEMALE_HINTS)
                val swim = labelScore(CLS_SWIMWEAR)
                val revealing = labelScore(CLS_REVEALING)

                // קודם NudeNet — הכי מדויק לנשים וללבוש
                if (nudeVerdict(bmp, cfg) { checkWomen(bmp, persons, cfg.copy(women = WomenLevel.ALL), hints, swim, revealing) }) {
                    reasons += "WOMEN_NUDENET"
                } else if (checkWomen(bmp, persons, cfg, hints, swim, revealing)) {
                    // גיבוי: פנים + מודל מגדר + אחוז עור חשוף
                    reasons += "WOMEN"
                }
            }
        } finally {
            if (bmp !== bitmap) bmp.recycle()
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
    private fun nudeVerdict(bmp: Bitmap, cfg: ImageFilterConfig, womanSeenFallback: () -> Boolean): Boolean {
        val model = nude ?: return false
        val thr = nudeThreshold(cfg.sensitivity)
        val found = try {
            model.detect(bmp, minOf(0.2f, thr))
        } catch (e: Throwable) {
            Log.w(TAG, "nudenet: ${e.message}")
            return false
        }
        if (found.isNotEmpty()) {
            note("NudeNet: " + found.sortedByDescending { it.score }.take(5).joinToString(", ") { "${it.label} ${f2(it.score)}" })
        }
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
