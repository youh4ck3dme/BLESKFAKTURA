package sk.bizagent.efaktura

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


const val LOCAL_TEST_BANNER = "Lokálny testovací režim — nejde o produkčné doručenie."
const val LEGISLATIVE_NOT_CHECKED = "Legislatívna platnosť nebola overená."
const val XML_READ_LIMIT = 1_500_000

class XmlReadException(message: String) : Exception(message)

fun readCappedXmlBlocking(input: InputStream, limit: Int = XML_READ_LIMIT, active: () -> Unit = {}): ByteArray {
    input.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            active()
            val read = stream.read(buffer)
            if (read < 0) break
            if (total + read > limit) throw XmlReadException("XML prekračuje limit $limit bajtov.")
            total += read
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

suspend fun readCappedXml(input: InputStream, limit: Int = XML_READ_LIMIT): ByteArray {
    return withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { input.close() } }
            val outcome = runCatching { readCappedXmlBlocking(input, limit) { ensureActive() } }
            outcome.fold(
                onSuccess = { bytes -> continuation.resume(bytes) { _, _, _ -> } },
                onFailure = { error ->
                    when {
                        error is kotlinx.coroutines.CancellationException -> {
                            if (continuation.isActive) continuation.cancel(error)
                        }
                        !continuation.isActive -> Unit
                        error is XmlReadException -> continuation.resumeWithException(error)
                        error is IOException -> continuation.resumeWithException(
                            XmlReadException(error.message ?: "XML sa nepodarilo prečítať.")
                        )
                        else -> continuation.resumeWithException(error)
                    }
                }
            )
        }
    }
}

data class PresentedDocument(
    val id: String,
    val number: String?,
    val sha256: String?,
    val banner: String,
    val technicalPass: Boolean?,
    val technicalText: String,
    val slovakText: String,
    val slovakIssues: List<String>,
    val deliveryText: String,
    val productionEligible: Boolean?
)

fun presentDocument(document: DocumentDto): PresentedDocument {
    val report = document.validation
    val technicalText = when (report?.technicalPass) {
        true -> "Technická kontrola prešla (XML, XSD, EN 16931, Peppol). $LEGISLATIVE_NOT_CHECKED"
        false -> "Technická kontrola neprešla. $LEGISLATIVE_NOT_CHECKED"
        null -> "Technická kontrola ešte nebežala. $LEGISLATIVE_NOT_CHECKED"
    }
    val slovakLayer = report?.layers?.firstOrNull { it.layer == "SK" }
    val slovakIssues = report?.issues?.filter { it.layer == "SK" }.orEmpty().map { "${it.ruleId}: ${it.message}" }
    val slovakText = when (slovakLayer?.status) {
        null -> "Slovenské pravidlá sa v tejto odpovedi nezobrazili. Absencia chyby nie je legislatívna platnosť."
        ValidationStatus.UNSUPPORTED -> "Slovenské pravidlá neboli spustené (${slovakLayer.rulesetVersion}). Doklad nie je pripravený na produkčné odoslanie."
        ValidationStatus.VALID -> "Slovenská vrstva je VALID. Samostatne to nestačí: productionEligible musí byť true a v tomto builde nie je."
        ValidationStatus.INVALID -> "Slovenské pravidlá doklad odmietli."
        ValidationStatus.UNAVAILABLE -> "Slovenské pravidlá sa nepodarilo spustiť."
        ValidationStatus.NOT_RUN -> "Slovenské pravidlá nebežali."
    }
    val delivery = document.delivery
    val deliveryText = if (delivery == null) {
        "Doručenie nie je evidované."
    } else {
        val mode = if (delivery.simulated || !delivery.productionReceipt) "simulované, bez produkčnej doručenky" else "označené ako produkčná doručenka"
        "Stav doručenia: ${delivery.status}. $mode."
    }
    return PresentedDocument(
        id = document.id,
        number = document.number,
        sha256 = document.sha256,
        banner = LOCAL_TEST_BANNER,
        technicalPass = report?.technicalPass,
        technicalText = technicalText,
        slovakText = slovakText,
        slovakIssues = slovakIssues,
        deliveryText = deliveryText,
        productionEligible = report?.productionEligible
    )
}

/**
 * Tenant-scoped bridge session. A new import gets a new idempotency key.
 * Retry repeats the same key and the same bytes. Account change cancels work and drops state.
 */
class EfakturaSession(
    private val scope: CoroutineScope,
    private val clientFor: (uid: String, tenantId: String) -> EfakturaClient
) {
    data class Operation(val key: String, val bytes: ByteArray, val uid: String, val tenantId: String)

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Ready(
            val items: List<DocumentDto>,
            val opened: PresentedDocument?,
            val error: String?
        ) : State
    }

    var state: State = State.Idle
        private set
    var onState: (State) -> Unit = {}
    private val operations = linkedMapOf<String, Operation>()
    private var job: Job? = null
    private var ownerUid: String? = null
    private var ownerTenant: String? = null

    fun onAccountChanged(uid: String?, tenantId: String?) {
        cancel()
        operations.clear()
        ownerUid = uid
        ownerTenant = tenantId
        setState(State.Idle)
    }

    fun importPrepared(bytes: ByteArray): String? {
        val uid = ownerUid ?: return null
        val tenantId = ownerTenant ?: return null
        if (bytes.size > XML_READ_LIMIT) {
            showError("XML prekračuje limit $XML_READ_LIMIT bajtov.")
            return null
        }
        val operation = Operation("import-${UUID.randomUUID()}", bytes.copyOf(), uid, tenantId)
        operations[operation.key] = operation
        launch(operation.key) { client ->
            val result = client.importXml(Direction.INBOUND, operation.bytes.inputStream(), operation.key)
            val document = result.document
            val items = client.listDocuments().items
            setState(State.Ready(items, document?.let(::presentDocument), null))
        }
        return operation.key
    }

    fun showError(message: String) {
        val ready = state as? State.Ready
        setState(State.Ready(ready?.items ?: emptyList(), ready?.opened, message))
    }

    fun retry(operationKey: String) {
        val operation = operations[operationKey] ?: return
        if (ownerUid != operation.uid || ownerTenant != operation.tenantId) return
        launch(operation.key) { client ->
            val result = client.importXml(Direction.INBOUND, operation.bytes.inputStream(), operation.key)
            val items = client.listDocuments().items
            setState(State.Ready(items, result.document?.let(::presentDocument), null))
        }
    }

    fun refresh() {
        launch("refresh") { client ->
            setState(State.Ready(client.listDocuments().items, opened(), null))
        }
    }

    fun open(id: String) {
        launch("open-$id") { client ->
            val document = presentDocument(client.getDocument(id))
            val items = (state as? State.Ready)?.items ?: client.listDocuments().items
            setState(State.Ready(items, document, null))
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (state is State.Loading) setState(State.Idle)
    }

    suspend fun join() {
        job?.join()
    }

    private fun opened(): PresentedDocument? = (state as? State.Ready)?.opened

    private fun launch(label: String, block: suspend (EfakturaClient) -> Unit) {
        val uid = ownerUid ?: return
        val tenantId = ownerTenant ?: return
        val client = clientFor(uid, tenantId)
        job?.cancel()
        setState(State.Loading)
        job = scope.launch {
            try {
                block(client)
                if (ownerUid != uid || ownerTenant != tenantId) setState(State.Idle)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (ownerUid == uid && ownerTenant == tenantId) {
                    setState(State.Ready((state as? State.Ready)?.items ?: emptyList(), opened(), error.message ?: label))
                }
            }
        }
    }

    private fun setState(next: State) {
        state = next
        onState(next)
    }
}
