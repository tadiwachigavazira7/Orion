// ============================================================
// data/enrollment/HttpEnrollmentVerifier.kt
// Real, network-calling EnrollmentVerifier implementation, talking to Orion's
// FastAPI enrollment backend (POST /enrollment). See core/enrollment/
// Enrollment.kt for the seam this implements and
// UnconfiguredEnrollmentVerifier for the honest placeholder this replaces
// once a backend base URL is configured (see MainActivity).
//
// Uses OkHttp for transport and Android's built-in org.json for the small,
// fixed-shape request/response bodies -- no JSON library dependency exists
// in this project by design (see DataStoreEnrollmentStore.kt), and pulling
// in Retrofit/Moshi/Gson/kotlinx.serialization for one endpoint would be
// disproportionate.
// ============================================================
package com.orion.data.enrollment

import com.orion.core.enrollment.EnrollmentCredential
import com.orion.core.enrollment.EnrollmentRequest
import com.orion.core.enrollment.EnrollmentResult
import com.orion.core.enrollment.EnrollmentVerifier
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * Backend response codes this endpoint is documented to return, per the
 * FastAPI enrollment service's route handler/error mapping.
 */
private const val HTTP_ENROLLED = 201
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409

/**
 * Real [EnrollmentVerifier] backed by Orion's FastAPI enrollment service's
 * `POST {baseUrl}/enrollment` endpoint.
 *
 * [baseUrl] carries no trailing slash assumption beyond simple concatenation
 * (e.g. `http://10.0.2.2:8000`); see MainActivity for how it's sourced from
 * `BuildConfig.ENROLLMENT_BASE_URL`. [client] is injectable for testability
 * (e.g. pointing at a MockWebServer instance) and defaults to a plain
 * [OkHttpClient] otherwise.
 */
class HttpEnrollmentVerifier(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) : EnrollmentVerifier {

    override suspend fun verify(request: EnrollmentRequest): EnrollmentResult =
        withContext(Dispatchers.IO) {
            try {
                val requestBody = JSONObject()
                    .put("organization_code", request.organizationCode)
                    .put("site_code", request.siteCode)
                    .put("device_id", request.deviceId)
                    .toString()
                    .toRequestBody(JSON_MEDIA_TYPE)

                val httpRequest = Request.Builder()
                    .url("$baseUrl/enrollment")
                    .post(requestBody)
                    .build()

                client.newCall(httpRequest).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    when (response.code) {
                        HTTP_ENROLLED -> approvedFrom(bodyString)
                        HTTP_NOT_FOUND, HTTP_CONFLICT ->
                            EnrollmentResult.Rejected(
                                extractDetail(bodyString)
                                    ?: "Enrollment was rejected (HTTP ${response.code})."
                            )
                        else -> EnrollmentResult.Failure(
                            "Unexpected response from the enrollment backend (HTTP ${response.code})."
                        )
                    }
                }
            } catch (e: CancellationException) {
                // Never swallow cancellation -- a cancelled coroutine must not keep running
                // and produce a result as if the call completed normally.
                throw e
            } catch (e: IOException) {
                // Network-level failure: unreachable host, connection reset, timeout, etc.
                EnrollmentResult.Failure("Could not reach the enrollment backend: ${e.message}")
            } catch (e: JSONException) {
                // Malformed response body on an otherwise-successful HTTP call.
                EnrollmentResult.Failure(
                    "The enrollment backend returned a malformed response: ${e.message}"
                )
            }
        }

    /**
     * [EnrollmentCredential.issuedAt] has no backend equivalent in the response, so it's set
     * to the receipt time on this side (only used for local bookkeeping). [EnrollmentCredential
     * .expiresAt] is always null: this backend has no credential-expiry/TTL concept, only
     * ACTIVE/REVOKED status via separate revocation. organizationId/siteId hold the
     * human-readable organization_code/site_code from the response, not backend-internal
     * UUIDs the client has no reason to know.
     */
    private fun approvedFrom(bodyString: String): EnrollmentResult.Approved {
        val json = JSONObject(bodyString)
        val credential = EnrollmentCredential(
            organizationId = json.getString("organization_code"),
            siteId = json.getString("site_code"),
            deviceId = json.getString("device_id"),
            token = json.getString("enrollment_credential"),
            issuedAt = System.currentTimeMillis(),
            expiresAt = null
        )
        return EnrollmentResult.Approved(credential)
    }

    private fun extractDetail(bodyString: String): String? =
        try {
            JSONObject(bodyString).optString("detail").takeIf { it.isNotBlank() }
        } catch (e: JSONException) {
            null
        }
}
