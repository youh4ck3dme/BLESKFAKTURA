package sk.bizagent.efaktura

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

class EfakturaClientTest {
    private val auth = AuthTokenProvider { "token" }
    private val appCheck = AppCheckTokenProvider { "appcheck" }

    @Test fun maps401403429AndDoesNotRetryMutations() = runBlocking {
        val server = MockWebServer()
        server.enqueue(error(401, "UNAUTHORIZED", "Token bol odmietnutý."))
        server.enqueue(error(403, "FORBIDDEN", "Nemáte oprávnenie na túto firmu."))
        server.enqueue(error(429, "RATE_LIMITED", "Príliš veľa požiadaviek."))
        server.enqueue(error(500, "VALIDATION_FAILED", "Server zlyhal."))
        server.start()
        val client = client(server)
        expect<EfakturaException.Unauthorized> { client.getDocument("1") }
        expect<EfakturaException.Forbidden> { client.getDocument("1") }
        expect<EfakturaException.RateLimited> { client.getDocument("1") }
        expect<EfakturaException.Api> { client.sendDocument("1", "idem-1") }
        val requests = buildList { repeat(server.requestCount) { add(server.takeRequest()) } }
        val sends = requests.filter { it.path?.contains("/send") == true }
        assertEquals(1, sends.size)
        assertEquals("idem-1", sends.single().getHeader("Idempotency-Key"))
        server.shutdown()
    }

    @Test fun keepsDecimalStrings() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"doc-1","payableAmount":"1656.25","number":"Snippet1","kind":"INVOICE","direction":"OUTBOUND"}"""))
        server.start()
        val doc = client(server).getDocument("doc-1")
        assertEquals("1656.25", doc.payableAmount)
        assertEquals(DocumentKind.INVOICE, doc.kind)
        server.shutdown()
    }

    @Test fun unreachableBackendIsTransport() = runBlocking {
        val server = MockWebServer()
        server.start()
        val url = server.url("/").toString()
        server.shutdown()
        val client = HttpEfakturaClient(url, "tenant", auth, appCheck)
        expect<EfakturaException.Transport> { client.getDocument("1") }
    }

    @Test fun cancellationIsNotSwallowed() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeadersDelay(3, TimeUnit.SECONDS).setBody("""{"items":[]}"""))
        server.start()
        val client = client(server)
        val job = launch {
            try {
                client.listDocuments()
                fail("mala vzniknúť cancellation")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail("cancellation bola prehltnutá: ${error::class.java.name}")
            }
        }
        delay(150)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        server.shutdown()
    }

    @Test fun sendsTenantOnEveryCallAndParsesNestedSend() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        server.enqueue(MockResponse().setBody("""{"id":"doc-9","delivery":{"status":"QUEUED","simulated":true,"productionReceipt":false}}"""))
        server.enqueue(MockResponse().setBody("""{"status":"PROVIDER_ACCEPTED","simulated":true,"productionReceipt":false}"""))
        server.start()
        val client = client(server)
        client.listDocuments()
        val sent = client.sendDocument("doc-9", "idem-send")
        val delivery = client.getDeliveryState("doc-9")
        val requests = buildList { repeat(3) { add(server.takeRequest()) } }
        assertTrue(requests.all { it.getHeader("X-Tenant-Id") == "tenant-a" })
        assertTrue(requests.all { it.path?.contains("/v1/tenants/tenant-a/") == true })
        assertEquals(DeliveryStatus.QUEUED, sent.delivery?.status)
        assertEquals(false, sent.delivery?.productionReceipt)
        assertEquals(DeliveryStatus.PROVIDER_ACCEPTED, delivery.status)
        assertEquals(false, delivery.productionReceipt)
        server.shutdown()
    }

    private fun client(server: MockWebServer): HttpEfakturaClient {
        val url = server.url("/").toString().replace("localhost", "127.0.0.1")
        return HttpEfakturaClient(url, "tenant-a", auth, appCheck)
    }

    private fun error(status: Int, code: String, message: String) = MockResponse()
        .setResponseCode(status)
        .setBody("""{"code":"$code","message":"$message","correlationId":"c1"}""")
        .addHeader("Content-Type", "application/json")

    private suspend inline fun <reified T : EfakturaException> expect(block: suspend () -> Unit) {
        try {
            block()
            fail("očakávaná ${T::class.java.simpleName}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: EfakturaException) {
            assertTrue(error is T)
        }
    }
}
