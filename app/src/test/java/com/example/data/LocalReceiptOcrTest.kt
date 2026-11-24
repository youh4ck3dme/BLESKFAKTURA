package com.example.data

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36])
class LocalReceiptOcrTest {
    private val text = "Syntetický obchod\nDOKLAD\n18.11.2026\nCelkom 12,30 EUR"
    private class FakeRecognizer(val task: Task<String>) : LocalReceiptOcr.Recognizer {
        lateinit var input: Bitmap
        var closes = 0
        var width = 0
        var height = 0
        override fun process(bitmap: Bitmap): Task<String> { input = bitmap; width = bitmap.width; height = bitmap.height; return task }
        override fun close() { closes++ }
    }
    @Test fun success_ownsBoundedInputAndDoesNotRecycleCallerBitmap() = runTest {
        val original = Bitmap.createBitmap(4000, 2000, Bitmap.Config.ARGB_8888)
        val recognizer = FakeRecognizer(Tasks.forResult(text))
        val result = LocalReceiptOcr.extract(original, StandardTestDispatcher(testScheduler), factory = { recognizer })
        assertTrue(result is AiExtractionResult.Success)
        assertNotSame(original, recognizer.input)
        assertEquals(1600, recognizer.width); assertEquals(800, recognizer.height)
        assertTrue(recognizer.input.isRecycled); assertEquals(1, recognizer.closes)
        assertFalse(original.isRecycled); original.recycle()
    }
    @Test fun smallBitmap_isCopiedBeforeAsyncRecognition() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val task = TaskCompletionSource<String>(); val recognizer = FakeRecognizer(task.task)
        val result = async { LocalReceiptOcr.extract(original, StandardTestDispatcher(testScheduler), factory = { recognizer }) }
        runCurrent(); assertNotSame(original, recognizer.input)
        original.recycle(); assertFalse(recognizer.input.isRecycled)
        task.setResult(text); runCurrent()
        assertTrue(result.await() is AiExtractionResult.Success); assertEquals(1, recognizer.closes)
    }
    @Test fun cancellation_doesNotReleaseInputUntilNativeTaskCompletes() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val task = TaskCompletionSource<String>(); val recognizer = FakeRecognizer(task.task)
        val job = launch { LocalReceiptOcr.extract(original, StandardTestDispatcher(testScheduler), factory = { recognizer }) }
        runCurrent(); job.cancel(); runCurrent()
        assertTrue(job.isCancelled); assertFalse(recognizer.input.isRecycled); assertEquals(0, recognizer.closes)
        task.setResult(text); runCurrent()
        assertTrue(recognizer.input.isRecycled); assertEquals(1, recognizer.closes)
        assertFalse(original.isRecycled); original.recycle()
    }
    @Test fun timeout_returnsTypedResultAndLateTaskReleasesItsOwnResources() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val task = TaskCompletionSource<String>(); val recognizer = FakeRecognizer(task.task)
        val result = async { LocalReceiptOcr.extract(original, StandardTestDispatcher(testScheduler), 100, { recognizer }) }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(AiExtractionResult.Timeout, result.await()); assertFalse(recognizer.input.isRecycled)
        task.setResult(text); runCurrent()
        assertTrue(recognizer.input.isRecycled); assertEquals(1, recognizer.closes)
        assertFalse(original.isRecycled); original.recycle()
    }
    @Test fun nativeFailure_isSanitizedAndClosesRecognizer() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val recognizer = FakeRecognizer(Tasks.forException(IllegalStateException("PRIVATE_SDK_MARKER")))
        val result = LocalReceiptOcr.extract(original, StandardTestDispatcher(testScheduler), factory = { recognizer })
        assertTrue(result is AiExtractionResult.ServiceError); assertFalse(result.toString().contains("PRIVATE_SDK_MARKER"))
        assertEquals(1, recognizer.closes); assertTrue(recognizer.input.isRecycled)
        assertFalse(original.isRecycled); original.recycle()
    }
    @Test fun factoryFailure_isSanitizedWithoutRecyclingCallerBitmap() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val result = LocalReceiptOcr.extract(original, StandardTestDispatcher(testScheduler), factory = { error("PRIVATE_SDK_MARKER") })
        assertTrue(result is AiExtractionResult.ServiceError); assertFalse(result.toString().contains("PRIVATE_SDK_MARKER"))
        assertFalse(original.isRecycled); original.recycle()
    }

    @Test fun outerDeadline_isPropagatedRatherThanReportedAsAnOcrTimeout() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val task = TaskCompletionSource<String>(); val recognizer = FakeRecognizer(task.task)
        var propagated = false
        try {
            withTimeout(50) {
                LocalReceiptOcr.extract(original, StandardTestDispatcher(testScheduler), 60_000, { recognizer })
            }
        } catch (_: TimeoutCancellationException) { propagated = true }
        assertTrue(propagated); assertEquals(0, recognizer.closes)
        assertFalse(recognizer.input.isRecycled)
        task.setResult(text); runCurrent()
        assertEquals(1, recognizer.closes); assertTrue(recognizer.input.isRecycled)
        assertFalse(original.isRecycled); original.recycle()
    }


    @Test fun retryWhileCancelledNativeTaskRuns_doesNotAllocateASecondRecognizer() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val task = TaskCompletionSource<String>(); val first = FakeRecognizer(task.task)
        val job = launch { LocalReceiptOcr.extract(original, StandardTestDispatcher(testScheduler), factory = { first }) }
        runCurrent(); job.cancel(); runCurrent()
        var created = false
        val busy = LocalReceiptOcr.extract(original, StandardTestDispatcher(testScheduler), factory = { created = true; error("No second native task") })
        assertTrue(busy is AiExtractionResult.ServiceError); assertFalse(created)
        task.setResult(text); runCurrent()
        val next = FakeRecognizer(Tasks.forResult(text))
        assertTrue(LocalReceiptOcr.extract(original, StandardTestDispatcher(testScheduler), factory = { next }) is AiExtractionResult.Success)
        assertEquals(1, first.closes); assertEquals(1, next.closes)
        original.recycle()
    }


    @Test fun memoryFailure_releasesOwnedCopyAndAllowsANewAttempt() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val result = LocalReceiptOcr.extract(original, StandardTestDispatcher(testScheduler), factory = { throw OutOfMemoryError("SYNTHETIC") })
        assertTrue(result is AiExtractionResult.ServiceError); assertFalse(original.isRecycled)
        val next = FakeRecognizer(Tasks.forResult(text))
        assertTrue(LocalReceiptOcr.extract(original, StandardTestDispatcher(testScheduler), factory = { next }) is AiExtractionResult.Success)
        assertEquals(1, next.closes); original.recycle()
    }

    @Test fun recycledInput_isRejectedBeforeRecognizerCreation() = runTest {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); original.recycle()
        val result = LocalReceiptOcr.extract(original, StandardTestDispatcher(testScheduler), factory = { error("Must not create recognizer") })
        assertTrue(result is AiExtractionResult.InvalidDocument)
    }
}
