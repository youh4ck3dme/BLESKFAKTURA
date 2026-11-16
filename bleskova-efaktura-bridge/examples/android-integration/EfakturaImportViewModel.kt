package com.example.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sk.bizagent.efaktura.Direction
import sk.bizagent.efaktura.EfakturaClient
import sk.bizagent.efaktura.EfakturaException
import sk.bizagent.efaktura.readCappedXmlBlocking
import java.io.InputStream
import java.util.UUID

/**
 * Príklad, NEZAPOJENÝ do MainActivity ani do existujúcich obrazoviek.
 * Nová logická operácia dostane nový Idempotency-Key. Retry tej istej operácie
 * pošle pôvodný kľúč a pôvodné bajty. Zmena účtu zruší bežiacu prácu.
 */
class EfakturaImportViewModel(
    private val scope: CoroutineScope,
    private val clientFor: (uid: String, tenantId: String) -> EfakturaClient
) {
    data class Operation(val key: String, val bytes: ByteArray, val uid: String, val tenantId: String)

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Success(val documentId: String, val sha256: String?) : State
        data class Error(val message: String) : State
    }

    var state: State = State.Idle
        private set
    private val operations = linkedMapOf<String, Operation>()
    private var job: Job? = null
    private var ownerUid: String? = null
    private var ownerTenant: String? = null

    fun onAccountChanged(uid: String?, tenantId: String?) {
        job?.cancel()
        job = null
        operations.clear()
        ownerUid = uid
        ownerTenant = tenantId
        state = State.Idle
    }

    fun import(xml: InputStream): String? {
        val uid = ownerUid ?: return null
        val tenantId = ownerTenant ?: return null
        val operation = Operation(key = "import-${UUID.randomUUID()}", bytes = readCappedXmlBlocking(xml), uid = uid, tenantId = tenantId)
        operations[operation.key] = operation
        launch(operation)
        return operation.key
    }

    fun retry(operationKey: String) {
        val operation = operations[operationKey] ?: return
        if (ownerUid != operation.uid || ownerTenant != operation.tenantId) return
        launch(operation)
    }

    suspend fun join() {
        job?.join()
    }

    private fun launch(operation: Operation) {
        val client = clientFor(operation.uid, operation.tenantId)
        state = State.Loading
        job = scope.launch {
            try {
                val result = client.importXml(Direction.INBOUND, operation.bytes.inputStream(), operation.key)
                if (ownerUid != operation.uid || ownerTenant != operation.tenantId) {
                    state = State.Idle
                    return@launch
                }
                state = State.Success(result.document?.id ?: "", result.document?.sha256)
            } catch (error: EfakturaException) {
                if (ownerUid == operation.uid && ownerTenant == operation.tenantId) state = State.Error(error.message ?: "Chyba")
            }
        }
    }
}
