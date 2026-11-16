package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.data.ReceiptImageProcessor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36])
class ReceiptImageProcessorTest {

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
