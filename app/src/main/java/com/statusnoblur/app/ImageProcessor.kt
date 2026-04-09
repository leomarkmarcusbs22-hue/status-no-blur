package com.statusnoblur.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageProcessor {

    // WhatsApp Status optimal dimensions
    private const val TARGET_WIDTH = 1080
    private const val TARGET_HEIGHT = 1920
    private const val JPEG_QUALITY = 95 // High quality input so WhatsApp compression does less damage

    fun optimizeForWhatsAppStatus(context: Context, sourceUri: Uri): File {
        val inputStream = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalArgumentException("Cannot open image")

        // Decode bounds first to calculate sample size
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()

        // Calculate sample size for memory efficiency
        val sampleSize = calculateSampleSize(options.outWidth, options.outHeight)

        // Decode with sample size
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val inputStream2 = context.contentResolver.openInputStream(sourceUri)!!
        val original = BitmapFactory.decodeStream(inputStream2, null, decodeOptions)!!
        inputStream2.close()

        // Step 1: Resize to optimal WhatsApp Status dimensions
        val resized = resizeToFit(original)
        if (resized != original) original.recycle()

        // Step 2: Apply sharpening to counteract WhatsApp's compression blur
        val sharpened = applySharpen(resized)
        if (sharpened != resized) resized.recycle()

        // Step 3: Boost contrast slightly to survive compression
        val enhanced = enhanceForCompression(sharpened)
        if (enhanced != sharpened) sharpened.recycle()

        // Step 4: Save as high-quality JPEG
        val outputDir = File(context.cacheDir, "optimized").apply { mkdirs() }
        val outputFile = File(outputDir, "status_${System.currentTimeMillis()}.jpg")

        FileOutputStream(outputFile).use { fos ->
            enhanced.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
        }
        enhanced.recycle()

        return outputFile
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        val maxDimension = max(TARGET_WIDTH, TARGET_HEIGHT) * 2
        while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun resizeToFit(source: Bitmap): Bitmap {
        val srcWidth = source.width
        val srcHeight = source.height

        // Calculate scale to fit within target dimensions while maintaining aspect ratio
        val scaleW = TARGET_WIDTH.toFloat() / srcWidth
        val scaleH = TARGET_HEIGHT.toFloat() / srcHeight
        val scale = min(scaleW, scaleH)

        // Only downscale, never upscale
        if (scale >= 1.0f) return source

        val newWidth = (srcWidth * scale).roundToInt()
        val newHeight = (srcHeight * scale).roundToInt()

        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

    private fun applySharpen(source: Bitmap): Bitmap {
        // Unsharp mask technique: subtract a slightly blurred version to enhance edges
        val width = source.width
        val height = source.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val sharpened = IntArray(width * height)
        val strength = 0.3f // Moderate sharpening - enough to counteract WhatsApp blur

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x

                // 3x3 kernel neighbors
                val top = pixels[(y - 1) * width + x]
                val bottom = pixels[(y + 1) * width + x]
                val left = pixels[y * width + (x - 1)]
                val right = pixels[y * width + (x + 1)]
                val center = pixels[idx]

                // For each channel, apply unsharp mask
                val r = sharpenChannel(center shr 16 and 0xFF, top shr 16 and 0xFF,
                    bottom shr 16 and 0xFF, left shr 16 and 0xFF, right shr 16 and 0xFF, strength)
                val g = sharpenChannel(center shr 8 and 0xFF, top shr 8 and 0xFF,
                    bottom shr 8 and 0xFF, left shr 8 and 0xFF, right shr 8 and 0xFF, strength)
                val b = sharpenChannel(center and 0xFF, top and 0xFF,
                    bottom and 0xFF, left and 0xFF, right and 0xFF, strength)
                val a = center ushr 24

                sharpened[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        // Copy edges as-is
        for (x in 0 until width) {
            sharpened[x] = pixels[x]
            sharpened[(height - 1) * width + x] = pixels[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            sharpened[y * width] = pixels[y * width]
            sharpened[y * width + width - 1] = pixels[y * width + width - 1]
        }

        result.setPixels(sharpened, 0, width, 0, 0, width, height)
        return result
    }

    private fun sharpenChannel(center: Int, top: Int, bottom: Int, left: Int, right: Int, strength: Float): Int {
        val blur = (top + bottom + left + right) / 4
        val detail = center - blur
        val sharpened = center + (detail * strength).roundToInt()
        return sharpened.coerceIn(0, 255)
    }

    private fun enhanceForCompression(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        // Slight contrast boost (1.05x) and minor saturation boost (1.1x)
        // This counteracts the washed-out look from WhatsApp compression
        val contrastMatrix = ColorMatrix().apply {
            val contrast = 1.05f
            val offset = (-0.5f * contrast + 0.5f) * 255f
            set(floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            ))
        }

        val saturationMatrix = ColorMatrix().apply {
            setSaturation(1.1f)
        }

        val combined = ColorMatrix().apply {
            postConcat(contrastMatrix)
            postConcat(saturationMatrix)
        }

        paint.colorFilter = ColorMatrixColorFilter(combined)
        canvas.drawBitmap(source, 0f, 0f, paint)

        return result
    }
}
