package com.meni.whitelistfilter.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * המודל המקומי. שני מודלים קטנים שרצים על המכשיר, בלי אינטרנט:
 *  - EfficientDet-Lite0 — מוצא אובייקטים בתמונה (אדם, רכב, בעלי חיים...)
 *  - EfficientNet-Lite0 — מסווג את כל התמונה (נוף, חוף, הר, סוג רכב...)
 * התוצאה: אילו סוגים מהרשימה נמצאו בתמונה.
 */
object ImageJudge {
    private const val TAG = "ImageJudge"
    private const val DETECTOR = "models/efficientdet_lite0.tflite"
    private const val CLASSIFIER = "models/efficientnet_lite0.tflite"

    private var detector: ObjectDetector? = null
    private var classifier: ImageClassifier? = null
    private var loaded = false

    @Volatile
    var error: String? = null
        private set

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

    /** במודל ImageNet, המחלקות הראשונות (עד 397 בערך) הן בעלי חיים */
    private const val LAST_ANIMAL_INDEX = 398

    @Synchronized
    private fun ensure(ctx: Context): Boolean {
        if (loaded) return detector != null || classifier != null
        loaded = true
        try {
            detector = ObjectDetector.createFromOptions(
                ctx,
                ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(DETECTOR).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(15)
                    .setScoreThreshold(0.2f)
                    .build()
            )
        } catch (e: Throwable) {
            error = "מודל הזיהוי לא נטען: ${e.message}"
            Log.e(TAG, "detector", e)
        }
        try {
            classifier = ImageClassifier.createFromOptions(
                ctx,
                ImageClassifier.ImageClassifierOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(CLASSIFIER).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(8)
                    .setScoreThreshold(0.03f)
                    .build()
            )
        } catch (e: Throwable) {
            error = (error?.let { "$it\n" } ?: "") + "מודל הסיווג לא נטען: ${e.message}"
            Log.e(TAG, "classifier", e)
        }
        return detector != null || classifier != null
    }

    /**
     * מחזיר את הסוגים שנמצאו בתמונה, מתוך [wanted] בלבד.
     * זורק חריגה אם המודל לא זמין — כדי שהקורא יחליט מה לעשות בתמונה שלא נבדקה.
     */
    @Synchronized
    fun find(ctx: Context, bitmap: Bitmap, wanted: Set<ImageCategory>, threshold: Float): Set<ImageCategory> {
        if (wanted.isEmpty()) return emptySet()
        if (!ensure(ctx)) throw IllegalStateException(error ?: "המודל לא זמין")

        val bmp = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val image = BitmapImageBuilder(bmp).build()
        val found = HashSet<ImageCategory>()
        val imageArea = (bmp.width * bmp.height).toFloat()

        // 1) זיהוי אובייקטים
        val det = detector
        if (det != null && (ImageCategory.PEOPLE in wanted || ImageCategory.VEHICLES in wanted || ImageCategory.ANIMALS in wanted)) {
            val result = det.detect(image)
            for (d in result.detections()) {
                val box = d.boundingBox()
                // אובייקט זעיר בפינה של התמונה לא נחשב
                if (box.width() * box.height() < imageArea * 0.01f) continue
                for (c in d.categories()) {
                    if (c.score() < threshold) continue
                    val name = c.categoryName().lowercase()
                    when (name) {
                        in DET_PEOPLE -> found += ImageCategory.PEOPLE
                        in DET_VEHICLES -> found += ImageCategory.VEHICLES
                        in DET_ANIMALS -> found += ImageCategory.ANIMALS
                    }
                }
            }
        }

        // 2) סיווג כל התמונה. סוכמים את הציונים לכל סוג, כי המודל מתפזר בין תוויות דומות
        val cls = classifier
        if (cls != null) {
            val result = cls.classify(image)
            val sums = HashMap<ImageCategory, Float>()
            result.classificationResult().classifications().firstOrNull()?.categories()?.forEach { c ->
                val name = c.categoryName().lowercase()
                val cat = when {
                    name in CLS_LANDSCAPES -> ImageCategory.LANDSCAPES
                    name in CLS_VEHICLES -> ImageCategory.VEHICLES
                    name in CLS_PEOPLE -> ImageCategory.PEOPLE
                    c.index() in 1..LAST_ANIMAL_INDEX -> ImageCategory.ANIMALS
                    else -> null
                }
                if (cat != null) sums[cat] = (sums[cat] ?: 0f) + c.score()
            }
            sums.forEach { (cat, score) -> if (score >= threshold) found += cat }
        }

        if (bmp !== bitmap) bmp.recycle()
        return found intersect wanted
    }
}
