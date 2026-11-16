package sk.bizagent.efaktura

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BridgeLiveTest {
    @Test fun kotlinClientUsesTheRealBridge() = runBlocking {
        val module = File(System.getProperty("user.dir")).resolve("../..").canonicalFile
        val entry = File(module, "dist/apps/api/src/index.js")
        assertTrue("Najprv spusti npm test alebo npm run build v module. Chýba ${entry.path}", entry.isFile)
        val invoice = File(module, "fixtures/peppol-official/base-example.xml").readBytes()
        val draftJson = File(module, "fixtures/synthetic/base-example.outbound.json").readText(StandardCharsets.UTF_8)
        val data = java.nio.file.Files.createTempDirectory("efaktura-live")
        val secret = "local-only-not-a-production-secret"
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder("node", "--disable-warning=ExperimentalWarning", "dist/apps/api/src/index.js")
            .directory(module)
            .redirectErrorStream(true)
            .apply {
                environment()["EFAKTURA_ENV"] = "local"
                environment()["EFAKTURA_AUTH_MODE"] = "emulator"
                environment()["EFAKTURA_PROVIDER"] = "local-sandbox"
                environment()["EFAKTURA_LOCAL_SEED"] = "1"
                environment()["EFAKTURA_STORE"] = "sqlite"
                environment()["EFAKTURA_DATA_DIR"] = data.toString()
                environment()["EFAKTURA_VALIDATOR_CACHE"] = data.resolve("cache").toString()
                environment()["EFAKTURA_PORT"] = port.toString()
                environment()["EFAKTURA_EMULATOR_SECRET"] = secret
                environment()["EFAKTURA_SANDBOX_WEBHOOK_SECRET"] = "local-sandbox-webhook-not-production"
            }
            .start()
        val output = StringBuilder()
        val pump = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> synchronized(output) { output.append(line).append('\n') } }
            }
        }
        pump.isDaemon = true
        pump.start()
        try {
            val base = waitForListening(process, output)
            val exp = System.currentTimeMillis() + 30 * 60_000
            val buyer = client(base, "tenant-buyer", secret, "uid-buyer", exp)
            val supplier = client(base, "tenant-supplier", secret, "uid-supplier", exp)
            val imported = buyer.importXml(Direction.INBOUND, invoice.inputStream(), "live-import")
            val document = imported.document ?: error("import bez dokladu")
            assertEquals("Snippet1", document.number)
            assertEquals(true, document.validation?.technicalPass)
            assertEquals(false, document.validation?.productionEligible)
            val loaded = buyer.getDocument(document.id)
            assertEquals(document.sha256, loaded.sha256)
            assertTrue(buyer.downloadOriginal(document.id).contentEquals(invoice))
            try {
                supplier.getDocument(document.id)
                error("cudzí tenant prečítal doklad")
            } catch (error: EfakturaException.Api) {
                assertEquals(404, error.status)
            }
            val draft = supplier.createDraftJson(draftJson, "live-draft")
            val report = supplier.validateDraft(draft.id, "live-validate")
            assertEquals(true, report.technicalPass)
            assertEquals(false, report.productionEligible)
            val issued = supplier.issueDraft(draft.id, "live-issue")
            val queued = supplier.sendDocument(issued.id, "live-send")
            assertEquals(DeliveryStatus.QUEUED, queued.delivery?.status)
            assertEquals(false, queued.delivery?.productionReceipt)
            val deadline = System.nanoTime() + 20_000_000_000L
            var delivery = supplier.getDeliveryState(issued.id)
            while (delivery.status != DeliveryStatus.PROVIDER_ACCEPTED && System.nanoTime() < deadline) {
                Thread.sleep(200)
                delivery = supplier.getDeliveryState(issued.id)
            }
            assertEquals(DeliveryStatus.PROVIDER_ACCEPTED, delivery.status)
            assertEquals(true, delivery.simulated)
            assertFalse(delivery.productionReceipt)
        } finally {
            process.destroy()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            process.destroyForcibly()
            File(data.toString()).deleteRecursively()
        }
    }

    private fun waitForListening(process: Process, output: StringBuilder): String {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            val text = synchronized(output) { output.toString() }
            val match = Regex(""""listening":"(http://127\.0\.0\.1:\d+)"""").find(text)
            if (match != null) return match.groupValues[1]
            if (!process.isAlive) error("Server skončil.\n$text")
            Thread.sleep(100)
        }
        error("Server nepočúva.\n${synchronized(output) { output.toString() }}")
    }

    private fun client(base: String, tenantId: String, secret: String, uid: String, exp: Long): HttpEfakturaClient {
        return HttpEfakturaClient(
            base,
            tenantId,
            AuthTokenProvider { sign(secret, """{"uid":"$uid","typ":"user","exp":$exp}""") },
            AppCheckTokenProvider { sign(secret, """{"typ":"appcheck","exp":$exp}""") }
        )
    }

    private fun sign(secret: String, json: String): String {
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val sig = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)))
        return "emu.$body.$sig"
    }
}
