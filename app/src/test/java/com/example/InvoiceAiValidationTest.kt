package com.example

import android.graphics.Bitmap
import com.example.data.AiExtractionResult
import com.example.data.InvoiceAiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36])
@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceAiValidationTest {

    @Test
    fun extractionDeadline_cancelsRequestAndRecyclesOnlyTheScaledBitmap() = runTest {
        val original = Bitmap.createBitmap(1602, 800, Bitmap.Config.ARGB_8888)
        var requestedBitmap: Bitmap? = null
        var requestCancelled = false
        try {
            val result = InvoiceAiService.extractInvoiceData(original, StandardTestDispatcher(testScheduler)) { image, _ ->
                requestedBitmap = image
                try {
                    awaitCancellation()
                } finally {
                    requestCancelled = true
                }
            }

            assertSame(AiExtractionResult.Timeout, result)
            assertEquals(60_000L, currentTime)
            assertTrue(requestCancelled)
            val scaled = checkNotNull(requestedBitmap)
            assertNotSame(original, scaled)
            assertTrue(scaled.isRecycled)
            assertFalse(original.isRecycled)
        } finally {
            original.recycle()
        }
    }

    @Test
    fun externalCancellation_propagatesAndPreservesTheOriginalBitmap() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val cancellation = CancellationException("Synthetic upstream cancellation")
        try {
            try {
                InvoiceAiService.extractInvoiceData(original, StandardTestDispatcher(testScheduler)) { image, _ ->
                    assertSame(original, image)
                    throw cancellation
                }
                fail("Cancellation must propagate instead of becoming a service error")
            } catch (actual: CancellationException) {
                assertEquals(cancellation.message, actual.message)
            }
            assertFalse(original.isRecycled)
        } finally {
            original.recycle()
        }
    }

    @Test
    fun callerDeadline_propagatesInsteadOfBecomingTheServiceDeadline() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            try {
                withTimeout(1_000L) {
                    InvoiceAiService.extractInvoiceData(original, StandardTestDispatcher(testScheduler)) { _, _ ->
                        awaitCancellation()
                    }
                }
                fail("Caller deadline must propagate")
            } catch (_: TimeoutCancellationException) {
                assertEquals(1_000L, currentTime)
            }
            assertFalse(original.isRecycled)
        } finally {
            original.recycle()
        }
    }

    @Test
    fun nullResponse_isInvalidDocumentRatherThanTimeout() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            val result = InvoiceAiService.extractInvoiceData(original, StandardTestDispatcher(testScheduler)) { _, _ -> null }
            assertTrue(result is AiExtractionResult.InvalidDocument)
            assertEquals(0L, currentTime)
        } finally {
            original.recycle()
        }
    }

    @Test
    fun validResponseBeforeDeadline_isParsedSuccessfully() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            val result = InvoiceAiService.extractInvoiceData(original, StandardTestDispatcher(testScheduler)) { _, _ ->
                delay(2_000L)
                """{"isAccountingDocument":true,"supplierName":"Synthetic merchant","issueDate":"2026-11-17","totalAmount":12.30}"""
            }
            assertTrue(result is AiExtractionResult.Success)
            assertEquals(12.30, (result as AiExtractionResult.Success).data.totalAmount, 0.001)
            assertEquals(2_000L, currentTime)
            assertFalse(original.isRecycled)
        } finally {
            original.recycle()
        }
    }

    @Test
    fun sdkException_doesNotExposeItsHttpBodyOrCredentialsInUiOrLogs() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        ShadowLog.clear()
        try {
            val result = InvoiceAiService.extractInvoiceData(original, StandardTestDispatcher(testScheduler)) { _, _ ->
                throw IllegalStateException(
                    "HTTP body: RECEIPT_MARKER; credentials: SECRET_MARKER",
                    IllegalArgumentException("Nested SECRET_MARKER RECEIPT_MARKER")
                )
            }
            assertTrue(result is AiExtractionResult.ServiceError)
            val message = (result as AiExtractionResult.ServiceError).message
            assertFalse(message.contains("SECRET_MARKER"))
            assertFalse(message.contains("RECEIPT_MARKER"))
            val logs = ShadowLog.getLogsForTag("InvoiceAiService")
            assertTrue(logs.isNotEmpty())
            logs.forEach { item ->
                assertEquals(null, item.throwable)
                assertFalse(item.msg.orEmpty().contains("SECRET_MARKER"))
                assertFalse(item.msg.orEmpty().contains("RECEIPT_MARKER"))
            }
            assertFalse(original.isRecycled)
        } finally {
            original.recycle()
        }
    }

    @Test
    fun emptyJson_isRejectedAsInvalidDocument() {
        val result = InvoiceAiService.parseAndValidateJson("{}")
        assertTrue(result is AiExtractionResult.InvalidDocument)
    }

    @Test
    fun nonAccountingDocumentFlag_isRejected() {
        val raw = """
            {
              "isAccountingDocument": false,
              "supplierName": "Selfie",
              "issueDate": "2026-11-16",
              "totalAmount": 10.0
            }
        """.trimIndent()
        val result = InvoiceAiService.parseAndValidateJson(raw)
        assertTrue(result is AiExtractionResult.InvalidDocument)
    }

    @Test
    fun zeroOrNegativeAmount_isRejected() {
        for (amount in listOf("0", "-1", "1000001", "null", "\"not-a-number\"")) {
            val raw = """{"isAccountingDocument":true,"supplierName":"Synthetic merchant","issueDate":"2026-11-18","totalAmount":$amount}"""
            assertTrue("Must reject invalid amount $amount", InvoiceAiService.parseAndValidateJson(raw) is AiExtractionResult.InvalidDocument)
        }
    }

    @Test
    fun malformedJsonAndDateWithTrailingText_areRejectedWithoutExposingThePayload() {
        for (raw in listOf("not-json PRIVATE_DOCUMENT_MARKER", """{"supplierName":"Synthetic merchant","issueDate":"2026-11-18 extra","totalAmount":12.30}""")) {
            val result = InvoiceAiService.parseAndValidateJson(raw)
            assertTrue(result is AiExtractionResult.InvalidDocument)
            assertFalse((result as AiExtractionResult.InvalidDocument).message.contains("PRIVATE_DOCUMENT_MARKER"))
        }
    }

    @Test
    fun validSlovakInvoice_isParsedAndNormalized() {
        val raw = """
            {
              "isAccountingDocument": true,
              "supplierName": "Tesco Stores SR, a.s.",
              "ico": "31322571",
              "dic": "2020301142",
              "issueDate": "16.11.2026",
              "totalAmount": 123.45,
              "vatAmount": 23.08,
              "vatRate": 23.0,
              "category": "Kancelária",
              "description": "Kancelársky papier"
            }
        """.trimIndent()
        val result = InvoiceAiService.parseAndValidateJson(raw)
        assertTrue(result is AiExtractionResult.Success)
        val data = (result as AiExtractionResult.Success).data
        assertEquals("Tesco Stores SR, a.s.", data.supplierName)
        assertEquals("2026-11-16", data.issueDate)
        assertEquals(123.45, data.totalAmount, 0.001)
        assertEquals(23.08, data.vatAmount!!, 0.001)
    }
}
