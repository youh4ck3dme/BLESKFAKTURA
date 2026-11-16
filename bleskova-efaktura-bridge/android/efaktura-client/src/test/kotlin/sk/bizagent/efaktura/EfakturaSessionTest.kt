package sk.bizagent.efaktura

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.io.IOException

class EfakturaSessionTest {
    @Test fun presentationSeparatesTechnicalSlovakAndDelivery() {
        val shown = presentDocument(
            DocumentDto(
                id = "doc-1",
                number = "Snippet1",
                sha256 = "abc",
                validation = ValidationReportDto(
                    status = ValidationStatus.UNSUPPORTED,
                    validatedSha256 = "abc",
                    productionEligible = false,
                    technicalPass = true,
                    layers = listOf(
                        LayerResultDto("XSD", ValidationStatus.VALID, "ubl"),
                        LayerResultDto("SK", ValidationStatus.UNSUPPORTED, "sk-xlsx-1.13-not-executed")
                    ),
                    issues = listOf(
                        ValidationIssueDto("SK", "WARNING", "SK_RULESET_NOT_EXECUTED", "Slovenský ruleset nebol spustený.")
                    )
                ),
                delivery = DeliveryDto(status = DeliveryStatus.PROVIDER_ACCEPTED, simulated = true, productionReceipt = false)
            )
        )
        assertEquals(LOCAL_TEST_BANNER, shown.banner)
        assertEquals(true, shown.technicalPass)
        assertTrue(shown.technicalText.contains(LEGISLATIVE_NOT_CHECKED))
        assertFalse(shown.technicalText.contains("právne neplatn"))
        assertFalse(shown.technicalText.contains("legislatívne platná faktúra"))
        assertTrue(shown.slovakText.contains("neboli spustené"))
        assertEquals(listOf("SK_RULESET_NOT_EXECUTED: Slovenský ruleset nebol spustený."), shown.slovakIssues)
        assertTrue(shown.deliveryText.contains("PROVIDER_ACCEPTED"))
        assertTrue(shown.deliveryText.contains("bez produkčnej doručenky"))
        assertEquals(false, shown.productionEligible)
    }

    @Test fun accountChangeCancelsAndClears() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val client = GateClient(gate)
        val session = EfakturaSession(CoroutineScope(Dispatchers.Unconfined)) { _, _ -> client }
        session.onAccountChanged("uid-a", "tenant-a")
        session.refresh()
        assertTrue(session.state is EfakturaSession.State.Loading)
        session.onAccountChanged("uid-b", "tenant-b")
        session.join()
        assertTrue(session.state is EfakturaSession.State.Idle)
        assertEquals(0, client.finished)
    }

    @Test fun importKeysStayDistinctAndRetryReusesBytes() = runBlocking {
        val client = MemoryClient()
        val session = EfakturaSession(CoroutineScope(Dispatchers.Unconfined)) { _, _ -> client }
        session.onAccountChanged(null, null)
        assertNull(session.importPrepared("a".toByteArray()))
        session.onAccountChanged("uid", "tenant")
        val first = session.importPrepared("alpha".toByteArray())
        session.join()
        val second = session.importPrepared("beta".toByteArray())
        session.join()
        session.retry(first!!)
        session.join()
        assertNotEquals(first, second)
        assertEquals(listOf(first, second, first), client.keys)
        assertEquals(listOf("alpha", "beta", "alpha"), client.payloads)
        val ready = session.state as EfakturaSession.State.Ready
        assertEquals(LOCAL_TEST_BANNER, ready.opened?.banner)
        assertEquals(listOf("alpha", "beta", "alpha"), client.payloads)
    }

    @Test fun cappedReadStopsDuringTheReadAndCancelClosesTheStream() = runBlocking {
        val endless = CountingStream()
        try {
            readCappedXml(endless, limit = 2_500)
            fail("limit mal zastaviť čítanie")
        } catch (error: XmlReadException) {
            assertTrue(error.message!!.contains("2 500") || error.message!!.contains("2500"))
        }
        assertTrue(endless.calls < 10)
        val gate = GateStream()
        val job = launch(Dispatchers.IO) { readCappedXml(gate) }
        delay(50)
        job.cancel()
        job.join()
        assertTrue(gate.closed)
    }
}

private class CountingStream : InputStream() {
    var calls = 0
    override fun read(): Int = 0x3C
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        calls += 1
        val n = minOf(len, 1_000)
        java.util.Arrays.fill(b, off, off + n, 0x3C.toByte())
        return n
    }
}

private class GateStream : InputStream() {
    @Volatile var closed = false
    private val lock = Object()
    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
    }
    override fun read(): Int = 0x3C
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        synchronized(lock) {
            while (!closed) lock.wait(50)
        }
        throw IOException("closed")
    }
}

private class GateClient(private val gate: CompletableDeferred<Unit>) : EfakturaClient {
    var finished = 0
    override suspend fun listDocuments(cursor: String?): ListResponseDto {
        gate.await()
        finished += 1
        return ListResponseDto(emptyList())
    }
    override suspend fun importXml(direction: Direction, xml: InputStream, idempotencyKey: String, maxBytes: Long) = error("unused")
    override suspend fun getDocument(id: String) = error("unused")
    override suspend fun downloadOriginal(id: String) = error("unused")
    override suspend fun createDraft(body: Map<String, Any?>, idempotencyKey: String) = error("unused")
    override suspend fun createDraftJson(json: String, idempotencyKey: String) = error("unused")
    override suspend fun validateDraft(id: String, idempotencyKey: String) = error("unused")
    override suspend fun issueDraft(id: String, idempotencyKey: String, expectedVersion: Int?) = error("unused")
    override suspend fun sendDocument(id: String, idempotencyKey: String) = error("unused")
    override suspend fun getDeliveryState(id: String) = error("unused")
}

private class MemoryClient : EfakturaClient {
    val keys = mutableListOf<String>()
    val payloads = mutableListOf<String>()
    override suspend fun importXml(direction: Direction, xml: InputStream, idempotencyKey: String, maxBytes: Long): ImportResponseDto {
        keys += idempotencyKey
        val text = xml.readBytes().toString(Charsets.UTF_8)
        payloads += text
        return ImportResponseDto(false, document = DocumentDto(id = "doc-${keys.size}", number = text, sha256 = "abc"))
    }
    override suspend fun listDocuments(cursor: String?) = ListResponseDto(listOf(DocumentDto(id = "doc-${keys.size}")))
    override suspend fun getDocument(id: String) = error("unused")
    override suspend fun downloadOriginal(id: String) = error("unused")
    override suspend fun createDraft(body: Map<String, Any?>, idempotencyKey: String) = error("unused")
    override suspend fun createDraftJson(json: String, idempotencyKey: String) = error("unused")
    override suspend fun validateDraft(id: String, idempotencyKey: String) = error("unused")
    override suspend fun issueDraft(id: String, idempotencyKey: String, expectedVersion: Int?) = error("unused")
    override suspend fun sendDocument(id: String, idempotencyKey: String) = error("unused")
    override suspend fun getDeliveryState(id: String) = error("unused")
}
