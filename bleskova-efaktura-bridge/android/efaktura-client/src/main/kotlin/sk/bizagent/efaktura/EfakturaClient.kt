package sk.bizagent.efaktura

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.JsonAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Direction { INBOUND, OUTBOUND }
enum class DocumentKind { INVOICE, CREDIT_NOTE }
enum class ValidationStatus { NOT_RUN, VALID, INVALID, UNAVAILABLE, UNSUPPORTED }
enum class DeliveryStatus {
    NOT_REQUESTED, QUEUED, SUBMITTING, PROVIDER_ACCEPTED, DELIVERED, REJECTED, UNKNOWN, FAILED
}

@JsonClass(generateAdapter = true)
data class MoneyDto(val amount: String, val currency: String)

@JsonClass(generateAdapter = true)
data class ApiErrorDto(val code: String, val message: String, val correlationId: String)

@JsonClass(generateAdapter = true)
data class ImportResponseDto(
    val deduplicated: Boolean,
    val code: String? = null,
    val document: DocumentDto? = null,
    val correlationId: String? = null
)

@JsonClass(generateAdapter = true)
data class DocumentDto(
    val id: String,
    val payableAmount: String? = null,
    val number: String? = null,
    val kind: DocumentKind? = null,
    val direction: Direction? = null,
    val sha256: String? = null,
    val lifecycle: String? = null,
    val version: Int? = null,
    val delivery: DeliveryDto? = null,
    val validation: ValidationReportDto? = null,
    val correlationId: String? = null
)

@JsonClass(generateAdapter = true)
data class ListResponseDto(val items: List<DocumentDto>, val nextCursor: String? = null, val correlationId: String? = null)

@JsonClass(generateAdapter = true)
data class ValidationReportDto(
    val status: ValidationStatus,
    val validatedSha256: String,
    val productionEligible: Boolean,
    val technicalPass: Boolean,
    val correlationId: String? = null
)

@JsonClass(generateAdapter = true)
data class DeliveryDto(
    val status: DeliveryStatus,
    val simulated: Boolean = false,
    val productionReceipt: Boolean = false,
    val providerMessageId: String? = null,
    val detail: String? = null,
    val correlationId: String? = null
)

fun interface AuthTokenProvider { suspend fun token(): String }
fun interface AppCheckTokenProvider { suspend fun token(): String }

interface EfakturaClient {
    suspend fun importXml(direction: Direction, xml: InputStream, idempotencyKey: String, maxBytes: Long = 1_500_000): ImportResponseDto
    suspend fun listDocuments(cursor: String? = null): ListResponseDto
    suspend fun getDocument(id: String): DocumentDto
    suspend fun downloadOriginal(id: String): ByteArray
    suspend fun createDraft(body: Map<String, Any?>, idempotencyKey: String): DocumentDto
    suspend fun createDraftJson(json: String, idempotencyKey: String): DocumentDto
    suspend fun validateDraft(id: String, idempotencyKey: String): ValidationReportDto
    suspend fun issueDraft(id: String, idempotencyKey: String, expectedVersion: Int? = null): DocumentDto
    /** POST /send returns the document. Delivery state is nested in [DocumentDto.delivery], not a top-level status. */
    suspend fun sendDocument(id: String, idempotencyKey: String): DocumentDto
    suspend fun getDeliveryState(id: String): DeliveryDto
}

sealed class EfakturaException(message: String) : Exception(message) {
    class Unauthorized(message: String) : EfakturaException(message)
    class Forbidden(message: String) : EfakturaException(message)
    class RateLimited(message: String) : EfakturaException(message)
    class Timeout(message: String) : EfakturaException(message)
    class Api(val status: Int, val code: String, message: String) : EfakturaException(message)
    class Transport(message: String) : EfakturaException(message)
}

class HttpEfakturaClient(
    baseUrl: String,
    private val tenantId: String,
    private val authTokens: AuthTokenProvider,
    private val appCheckTokens: AppCheckTokenProvider,
    http: OkHttpClient = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
) : EfakturaClient {
    private val moshi = Moshi.Builder().add(generatedAdapters()).build()
    private val api = Retrofit.Builder()
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(http)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(Api::class.java)
    private val errorAdapter = moshi.adapter(ApiErrorDto::class.java)
    private val mapAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    override suspend fun importXml(direction: Direction, xml: InputStream, idempotencyKey: String, maxBytes: Long): ImportResponseDto {
        val bytes = xml.use { stream ->
            val buffer = ByteArray(8192)
            val out = java.io.ByteArrayOutputStream()
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw EfakturaException.Api(413, "PAYLOAD_TOO_LARGE", "XML prekračuje limit.")
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        return call(api.importXml(tenantId, tenantId, direction.name, idempotencyKey, authHeader(), appCheckHeader(), bytes.toRequestBody("application/xml".toMediaType())))
    }

    override suspend fun listDocuments(cursor: String?) = call(api.list(tenantId, tenantId, cursor, authHeader(), appCheckHeader()))
    override suspend fun getDocument(id: String) = call(api.get(tenantId, tenantId, id, authHeader(), appCheckHeader()))
    override suspend fun downloadOriginal(id: String): ByteArray {
        val response = execute(api.original(tenantId, tenantId, id, authHeader(), appCheckHeader()))
        val body = response.body() ?: throw EfakturaException.Transport("Prázdna odpoveď.")
        return body.bytes()
    }
    override suspend fun createDraft(body: Map<String, Any?>, idempotencyKey: String): DocumentDto {
        val json = mapAdapter.toJson(body) ?: "{}"
        return createDraftJson(json, idempotencyKey)
    }
    override suspend fun createDraftJson(json: String, idempotencyKey: String): DocumentDto {
        return call(api.draft(tenantId, tenantId, idempotencyKey, authHeader(), appCheckHeader(), json.toRequestBody("application/json".toMediaType())))
    }
    override suspend fun validateDraft(id: String, idempotencyKey: String) = call(api.validate(tenantId, tenantId, id, idempotencyKey, authHeader(), appCheckHeader()))
    override suspend fun issueDraft(id: String, idempotencyKey: String, expectedVersion: Int?) = call(api.issue(tenantId, tenantId, id, idempotencyKey, expectedVersion?.toString(), authHeader(), appCheckHeader()))
    override suspend fun sendDocument(id: String, idempotencyKey: String) = call(api.send(tenantId, tenantId, id, idempotencyKey, authHeader(), appCheckHeader()))
    override suspend fun getDeliveryState(id: String) = call(api.delivery(tenantId, tenantId, id, authHeader(), appCheckHeader()))

    private suspend fun authHeader() = "Bearer ${authTokens.token()}"
    private suspend fun appCheckHeader() = appCheckTokens.token()

    private suspend fun <T> call(call: Call<T>): T {
        val response = execute(call)
        val body = response.body() ?: throw EfakturaException.Transport("Prázdna odpoveď.")
        return body
    }

    private suspend fun <T> execute(call: Call<T>): Response<T> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback<T> {
            override fun onResponse(call: Call<T>, response: Response<T>) {
                if (!response.isSuccessful) {
                    val raw = response.errorBody()?.string().orEmpty()
                    val parsed = runCatching { errorAdapter.fromJson(raw) }.getOrNull()
                    val message = parsed?.message ?: "Požiadavka zlyhala."
                    val error: EfakturaException = when (response.code()) {
                        401 -> EfakturaException.Unauthorized(message)
                        403 -> EfakturaException.Forbidden(message)
                        429 -> EfakturaException.RateLimited(message)
                        else -> EfakturaException.Api(response.code(), parsed?.code ?: "HTTP_${response.code()}", message)
                    }
                    if (cont.isActive) cont.resumeWithException(error)
                    return
                }
                if (cont.isActive) cont.resume(response)
            }
            override fun onFailure(call: Call<T>, t: Throwable) {
                if (cont.isCancelled || t is CancellationException || call.isCanceled) return
                val mapped = if (t is java.net.SocketTimeoutException) EfakturaException.Timeout(t.message ?: "Timeout") else EfakturaException.Transport(t.message ?: "Sieť zlyhala")
                if (cont.isActive) cont.resumeWithException(mapped)
            }
        })
    }
}

private fun generatedAdapters(): com.squareup.moshi.JsonAdapter.Factory = com.squareup.moshi.JsonAdapter.Factory { type, annotations, moshi ->
    if (annotations.isNotEmpty()) return@Factory null
    when (Types.getRawType(type)) {
        ApiErrorDto::class.java -> ApiErrorDtoJsonAdapter(moshi)
        ImportResponseDto::class.java -> ImportResponseDtoJsonAdapter(moshi)
        DocumentDto::class.java -> DocumentDtoJsonAdapter(moshi)
        ListResponseDto::class.java -> ListResponseDtoJsonAdapter(moshi)
        ValidationReportDto::class.java -> ValidationReportDtoJsonAdapter(moshi)
        DeliveryDto::class.java -> DeliveryDtoJsonAdapter(moshi)
        else -> null
    }
}

private interface Api {
    @POST("v1/tenants/{tenantId}/documents/import")
    fun importXml(
        @Path("tenantId") tenantId: String,
        @Header("X-Tenant-Id") tenantHeader: String,
        @Query("direction") direction: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("Authorization") authorization: String,
        @Header("X-Firebase-AppCheck") appCheck: String,
        @Body body: RequestBody
    ): Call<ImportResponseDto>

    @GET("v1/tenants/{tenantId}/documents")
    fun list(
        @Path("tenantId") tenantId: String,
        @Header("X-Tenant-Id") tenantHeader: String,
        @Query("cursor") cursor: String?,
        @Header("Authorization") authorization: String,
        @Header("X-Firebase-AppCheck") appCheck: String
    ): Call<ListResponseDto>

    @GET("v1/tenants/{tenantId}/documents/{id}")
    fun get(
        @Path("tenantId") tenantId: String,
        @Header("X-Tenant-Id") tenantHeader: String,
        @Path("id") id: String,
        @Header("Authorization") authorization: String,
        @Header("X-Firebase-AppCheck") appCheck: String
    ): Call<DocumentDto>

    @Streaming
    @GET("v1/tenants/{tenantId}/documents/{id}/original")
    fun original(
        @Path("tenantId") tenantId: String,
        @Header("X-Tenant-Id") tenantHeader: String,
        @Path("id") id: String,
        @Header("Authorization") authorization: String,
        @Header("X-Firebase-AppCheck") appCheck: String
    ): Call<ResponseBody>

    @POST("v1/tenants/{tenantId}/drafts")
    fun draft(
        @Path("tenantId") tenantId: String,
        @Header("X-Tenant-Id") tenantHeader: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("Authorization") authorization: String,
        @Header("X-Firebase-AppCheck") appCheck: String,
        @Body body: RequestBody
    ): Call<DocumentDto>

    @POST("v1/tenants/{tenantId}/drafts/{id}/validate")
    fun validate(
        @Path("tenantId") tenantId: String,
        @Header("X-Tenant-Id") tenantHeader: String,
        @Path("id") id: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("Authorization") authorization: String,
        @Header("X-Firebase-AppCheck") appCheck: String
    ): Call<ValidationReportDto>

    @POST("v1/tenants/{tenantId}/drafts/{id}/issue")
    fun issue(
        @Path("tenantId") tenantId: String,
        @Header("X-Tenant-Id") tenantHeader: String,
        @Path("id") id: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Expected-Version") expectedVersion: String?,
        @Header("Authorization") authorization: String,
        @Header("X-Firebase-AppCheck") appCheck: String
    ): Call<DocumentDto>

    @POST("v1/tenants/{tenantId}/documents/{id}/send")
    fun send(
        @Path("tenantId") tenantId: String,
        @Header("X-Tenant-Id") tenantHeader: String,
        @Path("id") id: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("Authorization") authorization: String,
        @Header("X-Firebase-AppCheck") appCheck: String
    ): Call<DocumentDto>

    @GET("v1/tenants/{tenantId}/documents/{id}/delivery")
    fun delivery(
        @Path("tenantId") tenantId: String,
        @Header("X-Tenant-Id") tenantHeader: String,
        @Path("id") id: String,
        @Header("Authorization") authorization: String,
        @Header("X-Firebase-AppCheck") appCheck: String
    ): Call<DeliveryDto>
}
