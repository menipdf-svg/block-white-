package com.meni.whitelistfilter.image

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * מודל קטן שמקבל תמונת פנים ומחזיר את ההסתברות שזו אישה.
 * המודל מהפרויקט Age-Gender_Estimation_TF-Android של Shubham Panchal (רישיון MIT).
 * קלט: תמונה 128×128 RGB בערכים 0..1. פלט: [גבר, אישה].
 */
class GenderModel(ctx: Context, assetPath: String) {
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(2) }
    private val pixels = IntArray(SIZE * SIZE)

    companion object {
        const val SIZE = 128
    }

    init {
        val fd = ctx.assets.openFd(assetPath)
        val buffer = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(2))
    }

    /** הסתברות 0..1 שהפנים בתמונה הן של אישה */
    @Synchronized
    fun femaleProbability(face: Bitmap): Float {
        val scaled = Bitmap.createScaledBitmap(face, SIZE, SIZE, true)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== face) scaled.recycle()

        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()
        interpreter.run(input, output)

        val male = output[0][0]
        val female = output[0][1]
        // אם הפלט עוד לא עבר softmax — מנרמלים
        val sum = male + female
        return if (male in 0f..1f && female in 0f..1f && sum in 0.98f..1.02f) female
        else {
            val m = exp(male.toDouble())
            val f = exp(female.toDouble())
            (f / (m + f)).toFloat()
        }
    }
}
