package com.example.blockwhite.filter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import android.util.LruCache

class TFLiteImageFilter(context: Context, modelFileName: String = "nsfw_model.tflite") {

    private var interpreter: Interpreter? = null
    // Cache של תוצאות לפי SHA-256 של התמונה (שומר עד 500 תוצאות בזיכרון)
    private val decisionCache = LruCache<String, Boolean>(500)

    init {
        try {
            val assetFileDescriptor = context.assets.openFd(modelFileName)
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            
            interpreter = Interpreter(mappedByteBuffer)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * מחזיר true אם התמונה כשרה/מותרת, ו-false אם התמונה חסומה.
     */
    fun isImageSafe(imageBytes: ByteArray): Boolean {
        if (imageBytes.isEmpty()) return true

        // בדיקה ב-Cache לפי Hash
        val hash = calculateHash(imageBytes)
        decisionCache.get(hash)?.let { cachedResult ->
            return cachedResult
        }

        val isSafe = analyzeImage(imageBytes)
        decisionCache.put(hash, isSafe)
        return isSafe
    }

    private fun analyzeImage(imageBytes: ByteArray): Boolean {
        val currentInterpreter = interpreter ?: return true

        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return true
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
            val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

            // הנחה: המודל מחזיר סיווג של 2 מחלקות [Unsafe, Safe]
            val output = Array(1) { FloatArray(2) }
            currentInterpreter.run(inputBuffer, output)

            val unsafeScore = output[0][0]
            val safeScore = output[0][1]

            // תנאי: אם ציון ה-Unsafe גבוה מ-0.5 התמונה תיחסם
            unsafeScore < 0.5f
        } catch (e: Exception) {
            e.printStackTrace()
            true // ברירת מחדל במקרה של תקלה
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * 224 * 224 * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(224 * 224)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixel in intValues) {
            byteBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            byteBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            byteBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        return byteBuffer
    }

    private fun calculateHash(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }
}

