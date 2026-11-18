package com.example

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.ReceiptImageProcessor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36])
class ReceiptImageProcessorTest {

    @Before
    fun rejectInvalidImageDataLikeTheAndroidDecoder() {
        // Robolectric's legacy decoder otherwise accepts arbitrary non-image byte streams.
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    @Test
    fun cameraCapture_usesPrivateContentUriAndRemovesOnlyStaleTemporaryCaptures() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "camera").apply { mkdirs() }
        val stale = File(directory, "test-stale.jpg").apply {
            writeText("stale")
            setLastModified(System.currentTimeMillis() - 90_000_000)
        }
        val fresh = File(directory, "test-fresh.jpg").apply { writeText("fresh") }
        try {
            val (uri, capture) = ReceiptImageProcessor.createCameraCaptureUri(context)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.fileprovider", uri.authority)
            assertEquals(directory.canonicalFile, capture.canonicalFile.parentFile)
            assertFalse(stale.exists())
            assertTrue(fresh.exists())
        } finally {
            stale.delete()
            fresh.delete()
        }
    }

    @Test
    fun corruptImport_isRejectedWithoutCreatingAnArchivedReceipt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "corrupt-test-image.jpg").apply { writeText("not an image") }
        val archive = File(context.filesDir, "receipts")
        val before = archive.list()?.toSet().orEmpty()
        try {
            try {
                ReceiptImageProcessor.decodeBoundedBitmapFromUri(context, Uri.fromFile(file))
                fail("Corrupt images must not enter the review flow")
            } catch (_: IllegalArgumentException) {
                assertEquals(before, archive.list()?.toSet().orEmpty())
            }
        } finally { file.delete() }
    }

    @Test
    fun validImport_isBoundedAndPreservesLandscapeAspect() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "large-test-image.png")
        val source = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val decoded = ReceiptImageProcessor.decodeBoundedBitmapFromUri(context, Uri.fromFile(file))
            try {
                assertTrue(decoded.bitmap.width <= 1600)
                assertTrue(decoded.bitmap.height <= 1600)
                assertEquals(2.0, decoded.bitmap.width.toDouble() / decoded.bitmap.height, 0.01)
                assertFalse(decoded.bitmap.isRecycled)
            } finally { decoded.bitmap.recycle() }
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test
    fun deleteArchivedReceipt_doesNotDeleteFilesOutsideItsPrivateArchive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unrelated = File(context.filesDir, "unrelated-test-file.jpg").apply { writeText("keep") }
        try {
            ReceiptImageProcessor.deleteArchivedReceipt(context, unrelated.absolutePath)
            assertTrue(unrelated.exists())
        } finally { unrelated.delete() }
    }

    @Test
    fun calculateInSampleSize_boundsLargeDimensions() {
        val sample = ReceiptImageProcessor.calculateInSampleSize(
            width = 4032,
            height = 3024,
            maxDimension = 1600
        )
        assertTrue(sample >= 2)
        assertEquals(1, ReceiptImageProcessor.calculateInSampleSize(1200, 900, 1600))
    }

    @Test
    fun scaleDownIfNeeded_preservesAspectAndLimitsMaxSide() {
        val large = Bitmap.createBitmap(3200, 1600, Bitmap.Config.ARGB_8888)
        val scaled = ReceiptImageProcessor.scaleDownIfNeeded(large, 1600)
        assertEquals(1600, scaled.width)
        assertEquals(800, scaled.height)
    }

    @Test
    fun archiveAndDeleteReceipt_writesInsideReceiptsDirAndCleansUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)

        val archivedPath = ReceiptImageProcessor.archiveReceiptBitmap(context, bitmap, "inv-123")
        assertNotNull(archivedPath)
        val file = File(archivedPath!!)
        assertTrue(file.exists())
        assertTrue(file.length() > 0L)

        ReceiptImageProcessor.deleteArchivedReceipt(context, archivedPath)
        assertFalse(file.exists())
    }
}
