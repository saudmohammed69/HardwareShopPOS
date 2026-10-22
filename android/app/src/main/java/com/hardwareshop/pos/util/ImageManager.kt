package com.hardwareshop.pos.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages product images stored in the app's private files directory.
 *
 * Images are:
 *   - Stored in  {filesDir}/product_images/{productId}.jpg
 *   - Compressed to max 512×512 px JPEG quality 80 on save
 *   - Referenced by their relative path (stored in ProductEntity.imagePath)
 *   - Loaded via Coil in the UI (handles caching automatically)
 *
 * The relative path format is "product_images/{filename}" so it can be
 * resolved regardless of the device's actual filesDir path.
 */
@Singleton
class ImageManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val IMAGE_DIR        = "product_images"
        private const val MAX_DIMENSION_PX = 512
        private const val JPEG_QUALITY     = 80
    }

    private val imagesDir: File
        get() = File(context.filesDir, IMAGE_DIR).also { it.mkdirs() }

    // ── Save ──────────────────────────────────────────────────────────────────

    /**
     * Saves an image from [uri] (camera or gallery) as a compressed JPEG.
     * Returns the relative path string to store in the database, or null on failure.
     */
    suspend fun saveImage(uri: Uri, productId: Long): String? = withContext(Dispatchers.IO) {
        try {
            val bitmap = decodeSampledBitmap(uri) ?: return@withContext null
            val scaled  = scaleBitmap(bitmap)
            val file    = File(imagesDir, "product_${productId}_${java.util.UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bitmap.recycle()
            if (scaled !== bitmap) scaled.recycle()
            val relativePath = "$IMAGE_DIR/${file.name}"
            Timber.d("Image saved: $relativePath (${file.length() / 1024} KB)")
            relativePath
        } catch (e: Exception) {
            Timber.e(e, "saveImage failed for productId=$productId")
            null
        }
    }

    // ── Resolve ───────────────────────────────────────────────────────────────

    /**
     * Resolves a relative path to an absolute [File].
     * Returns null if the path is null or the file doesn't exist.
     */
    fun resolveFile(relativePath: String?): File? {
        if (relativePath.isNullOrBlank()) return null
        if (!relativePath.matches(Regex("product_images/[A-Za-z0-9_.-]+"))) return null
        val file = File(context.filesDir, relativePath).canonicalFile
        return file.takeIf { it.parentFile == imagesDir.canonicalFile && it.isFile }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun decodeSampledBitmap(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // First pass: read dimensions only
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
                opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight)
                opts.inJustDecodeBounds = false
                null // Re-open the stream below
            }
            // Second pass: decode with calculated sample size
            context.contentResolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options()
                // Quick re-calculation without re-opening
                val fullOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, fullOpts)
                }
                opts.inSampleSize = calculateInSampleSize(fullOpts.outWidth, fullOpts.outHeight)
                BitmapFactory.decodeStream(input, null, opts)
            }
        } catch (e: Exception) {
            Timber.e(e, "decodeSampledBitmap failed")
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        if (height > MAX_DIMENSION_PX || width > MAX_DIMENSION_PX) {
            val halfHeight = height / 2
            val halfWidth  = width / 2
            while ((halfHeight / sampleSize) >= MAX_DIMENSION_PX ||
                   (halfWidth / sampleSize) >= MAX_DIMENSION_PX) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    private fun scaleBitmap(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        if (w <= MAX_DIMENSION_PX && h <= MAX_DIMENSION_PX) return src
        val scale  = MAX_DIMENSION_PX.toFloat() / maxOf(w, h)
        val newW   = (w * scale).toInt().coerceAtLeast(1)
        val newH   = (h * scale).toInt().coerceAtLeast(1)
        return src.scale(newW, newH, filter = true)
    }
}
