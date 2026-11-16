package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class DecodedReceiptImage(
    val bitmap: Bitmap,
    val displayName: String,
    val formattedSize: String
)

object ReceiptImageProcessor {
    private const val MAX_DECODED_DIMENSION = 1600
    private const val CAMERA_DIR = "camera"
    private const val RECEIPTS_DIR = "receipts"

    fun createCameraCaptureUri(context: Context): Pair<Uri, File> {
        val cameraDir = File(context.cacheDir, CAMERA_DIR).apply { mkdirs() }
        // Clean stale temporary camera captures older than 24 hours
        val cutoff = System.currentTimeMillis() - 86_400_000L
        cameraDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
        val captureFile = File(cameraDir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            captureFile
        )
        return uri to captureFile
    }

    suspend fun decodeBoundedBitmapFromUri(
        context: Context,
        uri: Uri,
        fallbackName: String = "doklad.jpg"
    ): DecodedReceiptImage = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (displayName, sizeLabel) = readUriMetadata(context, uri, fallbackName)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Obrázok sa nepodarilo načítať alebo je poškodený.")
        }

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODED_DIMENSION)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: throw IllegalArgumentException("Dekódovanie obrázka zlyhalo.")

        val exifOrientation = resolver.openInputStream(uri)?.use { stream ->
            runCatching {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val rotated = applyExifOrientation(rawBitmap, exifOrientation)
        val bounded = scaleDownIfNeeded(rotated, MAX_DECODED_DIMENSION)

        DecodedReceiptImage(
            bitmap = bounded,
            displayName = displayName,
            formattedSize = sizeLabel
        )
    }

    suspend fun archiveReceiptBitmap(
        context: Context,
        bitmap: Bitmap,
        invoiceId: String
    ): String? = withContext(Dispatchers.IO) {
        if (bitmap.isRecycled) return@withContext null
        val dir = File(context.filesDir, RECEIPTS_DIR).apply { mkdirs() }
        val safeId = invoiceId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val outFile = File(dir, "receipt_$safeId.jpg")
        try {
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos)
            }
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun deleteArchivedReceipt(context: Context, imagePath: String?) {
        if (imagePath.isNullOrBlank()) return
        runCatching {
            val file = File(imagePath)
            val receiptsRoot = File(context.filesDir, RECEIPTS_DIR).canonicalFile
            if (file.exists() && file.canonicalFile.parentFile == receiptsRoot) {
                file.delete()
            }
        }
    }

    private fun readUriMetadata(context: Context, uri: Uri, fallbackName: String): Pair<String, String> {
        var name = fallbackName
        var sizeStr = ""
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0) {
                        val bytes = cursor.getLong(sizeIdx)
                        if (bytes > 0L) {
                            sizeStr = if (bytes > 1_048_576L) {
                                String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
                            } else {
                                String.format(Locale.US, "%.1f KB", bytes / 1024.0)
                            }
                        }
                    }
                }
            }
        }
        return name to sizeStr
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var inSampleSize = 1
        while ((width / inSampleSize) > maxDim * 2 || (height / inSampleSize) > maxDim * 2) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        return try {
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed !== bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            transformed
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        if (scaled !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return scaled
    }
}
