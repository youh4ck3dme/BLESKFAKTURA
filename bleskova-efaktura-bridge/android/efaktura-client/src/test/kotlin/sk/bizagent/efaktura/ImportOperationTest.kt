package sk.bizagent.efaktura

import com.example.ui.EfakturaImportViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.InputStream

class ImportOperationTest {
    @Test fun twoDocumentsGetDistinctKeysAndRetryReusesTheFirst() = runBlocking {
        val client = RecordingClient()
        val model = EfakturaImportViewModel(CoroutineScope(Dispatchers.Unconfined)) { _, _ -> client }
        model.onAccountChanged("uid-buyer", "tenant-buyer")
        val first = model.import("alpha-invoice".byteInputStream())
        model.join()
        val second = model.import("beta-invoice".byteInputStream())
        model.join()
        model.retry(first!!)
        model.join()
        assertNotEquals(first, second)
        assertEquals(listOf(first, second, first), client.keys)
        assertEquals(listOf("alpha-invoice", "beta-invoice", "alpha-invoice"), client.payloads)
    }
}

private class RecordingClient : EfakturaClient {
    val keys = mutableListOf<String>()
    val payloads = mutableListOf<String>()

    override suspend fun importXml(direction: Direction, xml: InputStream, idempotencyKey: String, maxBytes: Long): ImportResponseDto {
        keys += idempotencyKey
        payloads += xml.readBytes().toString(Charsets.UTF_8)
        return ImportResponseDto(deduplicated = false, document = DocumentDto(id = "doc-${keys.size}", sha256 = "abc"))
    }
    override suspend fun listDocuments(cursor: String?) = error("unused")
    override suspend fun getDocument(id: String) = error("unused")
    override suspend fun downloadOriginal(id: String) = error("unused")
    override suspend fun createDraft(body: Map<String, Any?>, idempotencyKey: String) = error("unused")
    override suspend fun createDraftJson(json: String, idempotencyKey: String) = error("unused")
    override suspend fun validateDraft(id: String, idempotencyKey: String) = error("unused")
    override suspend fun issueDraft(id: String, idempotencyKey: String, expectedVersion: Int?) = error("unused")
    override suspend fun sendDocument(id: String, idempotencyKey: String) = error("unused")
    override suspend fun getDeliveryState(id: String) = error("unused")
}
