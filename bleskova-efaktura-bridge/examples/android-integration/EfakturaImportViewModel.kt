package com.example.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sk.bizagent.efaktura.Direction
import sk.bizagent.efaktura.EfakturaClient
import sk.bizagent.efaktura.EfakturaException
import java.io.InputStream

/**
 * Príklad, NEZAPOJENÝ do MainActivity ani do existujúcich obrazoviek.
 * Vlastník operácie je uid + tenantId zachytený na začiatku.
 * Zmena účtu zruší bežiacu prácu a vyčistí stav.
 */
class EfakturaImportViewModel(
    private val scope: CoroutineScope,
    private val clientFor: (uid: String, tenantId: String) -> EfakturaClient
) {
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Success(val documentId: String, val sha256: String?) : State
        data class Error(val message: String) : State
    }

    var state: State = State.Idle
        private set
    private var job: Job? = null
    private var ownerUid: String? = null
    private var ownerTenant: String? = null

    fun onAccountChanged(uid: String?, tenantId: String?) {
        job?.cancel()
        job = null
        ownerUid = uid
        ownerTenant = tenantId
        state = State.Idle
    }

    fun import(xml: InputStream) {
        val uid = ownerUid ?: return
        val tenantId = ownerTenant ?: return
        val client = clientFor(uid, tenantId)
        job?.cancel()
        state = State.Loading
        job = scope.launch {
            try {
                val result = client.importXml(Direction.INBOUND, xml, idempotencyKey = "import-$uid-$tenantId")
                if (ownerUid != uid || ownerTenant != tenantId) {
                    state = State.Idle
                    return@launch
                }
                state = State.Success(result.document?.id ?: "", result.document?.sha256)
            } catch (error: EfakturaException) {
                if (ownerUid == uid && ownerTenant == tenantId) state = State.Error(error.message ?: "Chyba")
            }
        }
    }
}
