package com.meni.whitelistfilter.image

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

data class NudeDetection(val label: String, val score: Float, val box: RectF)

/**
 * NudeNet (notAI-tech, רישיון AGPL-3.0) — מודל YOLOv8 שאומן לסינון תוכן.
 * מזהה פנים של אישה/גבר ואזורי גוף חשופים או מכוסים.
 * שתי גרסאות: 320n (קטן ומהיר) ו-640m (גדול ומדויק).
 * קלט: size×size RGB בערכים 0..1, NCHW, התמונה מרופדת לריבוע (ימינה ולמטה).
 * פלט: [1, 4+18, N] — מרכז, רוחב, גובה, ואז ציון לכל תווית.
 */
class NudeModel(ctx: Context, assetPath: String, private val size: Int) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    companion object {

        val LABELS = listOf(
            "FEMALE_GENITALIA_COVERED",
            "FACE_FEMALE",
            "BUTTOCKS_EXPOSED",
            "FEMALE_BREAST_EXPOSED",
            "FEMALE_GENITALIA_EXPOSED",
            "MALE_BREAST_EXPOSED",
            "ANUS_EXPOSED",
            "FEET_EXPOSED",
            "BELLY_COVERED",
            "FEET_COVERED",
            "ARMPITS_COVERED",
            "ARMPITS_EXPOSED",
            "FACE_MALE",
            "BELLY_EXPOSED",
            "MALE_GENITALIA_EXPOSED",
            "ANUS_COVERED",
            "FEMALE_BREAST_COVERED",
            "BUTTOCKS_COVERED"
        )
    }

    init {
        // מעתיקים את המודל לקובץ פעם אחת, וטוענים ממנו — בלי להחזיק עותק שלם שלו בזיכרון
        val file = java.io.File(ctx.filesDir, "models/" + assetPath.substringAfterLast('/'))
        val assetLength = try {
            ctx.assets.openFd(assetPath).use { it.length }
        } catch (_: Exception) {
            -1L
        }
        if (!file.exists() || (assetLength > 0 && file.length() != assetLength)) {
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.path + ".part")
            ctx.assets.open(assetPath).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(file)
        }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(if (size > 320) 4 else 2)
        }
        session = env.createSession(file.absolutePath, opts)
        inputName = session.inputNames.first()
    }

    @Synchronized
    fun detect(src: Bitmap, minScore: Float): List<NudeDetection> {
        // ריפוד לריבוע (שחור, ימינה ולמטה) והקטנה ל-320
        val side = max(src.width, src.height)
        val scale = size.toFloat() / side
        val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        square.eraseColor(Color.BLACK)
        val c = Canvas(square)
        c.scale(scale, scale)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))

        val px = IntArray(size * size)
        square.getPixels(px, 0, size, 0, 0, size, size)
        square.recycle()

        val plane = size * size
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = px[i]
            data[i] = ((p shr 16) and 0xFF) / 255f
            data[plane + i] = ((p shr 8) and 0xFF) / 255f
            data[2 * plane + i] = (p and 0xFF) / 255f
        }

        val raw = ArrayList<NudeDetection>()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = (result[0].value as Array<Array<FloatArray>>)[0] // [22][2100]
                val count = out[0].size
                val classes = min(LABELS.size, out.size - 4)
                for (i in 0 until count) {
                    var best = -1
                    var bestScore = 0f
                    for (k in 0 until classes) {
                        val sc = out[4 + k][i]
                        if (sc > bestScore) {
                            bestScore = sc
                            best = k
                        }
                    }
                    if (best < 0 || bestScore < minScore) continue
                    // קואורדינטות המודל → קואורדינטות התמונה המקורית
                    val cx = out[0][i] / scale
                    val cy = out[1][i] / scale
                    val w = out[2][i] / scale
                    val h = out[3][i] / scale
                    val box = RectF(
                        (cx - w / 2).coerceIn(0f, src.width.toFloat()),
                        (cy - h / 2).coerceIn(0f, src.height.toFloat()),
                        (cx + w / 2).coerceIn(0f, src.width.toFloat()),
                        (cy + h / 2).coerceIn(0f, src.height.toFloat())
                    )
                    raw += NudeDetection(LABELS[best], bestScore, box)
                }
            }
        }
        return nms(raw, 0.45f)
    }

    /** מסיר תיבות כפולות של אותה תווית */
    private fun nms(list: List<NudeDetection>, iouLimit: Float): List<NudeDetection> {
        val kept = ArrayList<NudeDetection>()
        for (d in list.sortedByDescending { it.score }) {
            if (kept.none { it.label == d.label && iou(it.box, d.box) > iouLimit }) kept += d
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }
}
