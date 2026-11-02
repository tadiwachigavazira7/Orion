// ============================================================
// data/enrollment/HttpEnrollmentVerifier.kt
// EnrollmentVerifier backed by Orion's FastAPI enrollment backend:
//   POST /enrollment                               (enroll)
//   POST /enrolled_devices/{device_id}/verify      (verify)
// Response mapping follows the shared enrollment v2 contract exactly; see the
// KDoc on enroll()/verify().
//
// Uses OkHttp for transport and the platform org.json for the small,
// fixed-shape bodies -- no JSON library dependency by design.
// Never logs or echoes the enrollment authorization or credential.
// ============================================================
package com.orion.data.enrollment

import com.orion.core.enrollment.EnrollmentCredential
import com.orion.core.enrollment.EnrollmentRequest
import com.orion.core.enrollment.EnrollmentResult
import com.orion.core.enrollment.EnrollmentVerifier
import com.orion.core.enrollment.ServerAuthorizationWindow
import com.orion.core.enrollment.VerificationResult
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** Response bodies are tiny; cap what is read so a misbehaving server can't exhaust memory. */
private const val MAX_RESPONSE_BYTES = 64L * 1024

private const val ERROR_INVALID_ENROLLMENT_AUTHORIZATION = "invalid_enrollment_authorization"
private val ENROLL_NOT_FOUND_CODES = setOf("organization_not_found", "site_not_found", "site_organization_mismatch")
private const val ERROR_DEVICE_ALREADY_ENROLLED = "device_already_enrolled"
private const val ERROR_DEVICE_LIMIT_REACHED = "device_limit_reached"
private const val ERROR_SUBSCRIPTION_INACTIVE = "subscription_inactive"
private const val ERROR_ENROLLMENT_RATE_LIMITED = "enrollment_rate_limited"
private const val ERROR_DEVICE_NOT_FOUND = "device_not_found"
private const val ERROR_INVALID_CREDENTIAL = "invalid_credential"
private const val ERROR_DEVICE_REVOKED = "device_revoked"

internal const val ALREADY_ENROLLED_MESSAGE =
    "This device is already enrolled. After an administrator resets this device, " +
        "enroll again with your store's enrollment auth. code."

// App-owned copy for invalid-code / capacity / subscription / rate-limit rejections: the server's
// `detail` is deliberately not shown for these, so wording stays consistent and nothing
// request-derived can ever be echoed back.
internal const val INVALID_ENROLLMENT_CODE_MESSAGE =
    "The enrollment auth. code was not accepted. Check it, or ask your store administrator for the current code."
internal const val DEVICE_LIMIT_REACHED_MESSAGE =
    "This store has reached its authorized device limit. Contact your store administrator."
internal const val SUBSCRIPTION_INACTIVE_MESSAGE =
    "This store's Orion subscription is not active. Contact your store administrator."
internal const val ENROLLMENT_RATE_LIMITED_MESSAGE =
    "Too many incorrect enrollment attempts. Wait a few minutes and try again."

/**
 * [baseUrl] is e.g. `http://10.0.2.2:8000` (debug/emulator) or the release
 * `https://...` URL, sourced from `BuildConfig.ENROLLMENT_BASE_URL` (see
 * MainActivity). [client] is injectable for tests (MockWebServer). Redirects
 * are disabled on whatever client is supplied, so a redirect can never
 * downgrade or re-target a credential-bearing request.
 */
class HttpEnrollmentVerifier(
    private val baseUrl: String,
    client: OkHttpClient = OkHttpClient()
) : EnrollmentVerifier {

    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * | Response                                         | Result           |
     * |--------------------------------------------------|------------------|
     * | 201 (well-formed)                                | Approved         |
     * | 201 (unreadable) -- server approved, we can't use it | RecoveryRequired |
     * | 403 invalid_enrollment_authorization, 404 org/site codes, 422 | Rejected |
     * | 403 subscription_inactive, 409 device_limit_reached, 429 enrollment_rate_limited | Rejected (app-owned message) |
     * | 409 device_already_enrolled                      | RecoveryRequired |
     * | I/O failure after the request was sent (outcome unknown) | RecoveryRequired |
     * | I/O failure before sending, 5xx, anything else   | Failure          |
     *
     * `enrollment_authorization` carries the store's reusable enrollment code (typed by
     * the employee); the server normalizes it, so it is sent as entered (trimmed only).
     */
    override suspend fun enroll(request: EnrollmentRequest): EnrollmentResult = withContext(Dispatchers.IO) {
        val url = endpoint("enrollment")
            ?: return@withContext EnrollmentResult.Failure("The enrollment backend URL is not valid.")
        val body = JSONObject()
            .put("organization_code", request.organizationCode)
            .put("site_code", request.siteCode)
            .put("device_id", request.deviceId)
            .put("enrollment_authorization", request.enrollmentAuthorization)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        // Track whether the request reached the wire, so a lost response (the server may
        // already have activated the device) is distinguishable from "never sent".
        val requestSent = AtomicBoolean(false)
        val enrollClient = client.newBuilder()
            .retryOnConnectionFailure(false) // never silently resend: a lost response must surface as "outcome unknown"
            .eventListener(object : EventListener() {
                override fun requestHeadersStart(call: Call) {
                    requestSent.set(true)
                }
            })
            .build()

        try {
            enrollClient.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                mapEnrollResponse(response, request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (requestSent.get()) {
                EnrollmentResult.RecoveryRequired(
                    "The enrollment response was lost, so the outcome is unknown. Try again; if the " +
                        "device is reported as already enrolled, ask an administrator to reset this device."
                )
            } else {
                EnrollmentResult.Failure(
                    "Could not reach the enrollment backend. Enrollment requires a network connection."
                )
            }
        }
    }

    /**
     * | Response                   | Result            |
     * |----------------------------|-------------------|
     * | 200 (well-formed, ACTIVE)  | Authorized        |
     * | 401 invalid_credential     | InvalidCredential |
     * | 403 device_revoked         | Revoked           |
     * | 404 device_not_found       | UnknownDevice     |
     * | anything else / I/O / malformed | Unavailable (never a denial) |
     *
     * Denials require BOTH the status and the documented `error_code`, so a proxy,
     * captive portal, or misrouted URL returning a bare 401/403/404 cannot lock the
     * device out; it is treated as "verification unavailable" instead.
     */
    override suspend fun verify(credential: EnrollmentCredential): VerificationResult = withContext(Dispatchers.IO) {
        val url = endpoint("enrolled_devices", credential.deviceId, "verify")
            ?: return@withContext VerificationResult.Unavailable("The enrollment backend URL is not valid.")
        val body = JSONObject()
            .put("enrollment_credential", credential.token)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        try {
            client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                mapVerifyResponse(response, credential)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            VerificationResult.Unavailable("Could not reach the enrollment backend.")
        }
    }

    private fun mapEnrollResponse(response: Response, request: EnrollmentRequest): EnrollmentResult {
        val json = readJson(response)
        val errorCode = json?.optString("error_code").orEmpty()
        return when {
            response.code == 201 -> approvedFrom(json, request)
                ?: EnrollmentResult.RecoveryRequired(
                    "The backend approved enrollment but its response could not be read. " +
                        "Ask an administrator to reset this device, then enroll again."
                )

            response.code == 403 && errorCode == ERROR_INVALID_ENROLLMENT_AUTHORIZATION ->
                EnrollmentResult.Rejected(INVALID_ENROLLMENT_CODE_MESSAGE)

            response.code == 404 && errorCode in ENROLL_NOT_FOUND_CODES ->
                EnrollmentResult.Rejected(
                    safeDetail(json, request) ?: "The organization or site code was not recognised."
                )

            response.code == 422 ->
                EnrollmentResult.Rejected("The enrollment request was incomplete. Fill in every field and try again.")

            response.code == 403 && errorCode == ERROR_SUBSCRIPTION_INACTIVE ->
                EnrollmentResult.Rejected(SUBSCRIPTION_INACTIVE_MESSAGE)

            // Capacity is a store-level limit, not this device's state: never RecoveryRequired.
            response.code == 409 && errorCode == ERROR_DEVICE_LIMIT_REACHED ->
                EnrollmentResult.Rejected(DEVICE_LIMIT_REACHED_MESSAGE)

            response.code == 409 && errorCode == ERROR_DEVICE_ALREADY_ENROLLED ->
                EnrollmentResult.RecoveryRequired(ALREADY_ENROLLED_MESSAGE)

            // Temporary; the user may retry later. Retry-After is not needed for the static copy.
            response.code == 429 && errorCode == ERROR_ENROLLMENT_RATE_LIMITED ->
                EnrollmentResult.Rejected(ENROLLMENT_RATE_LIMITED_MESSAGE)

            else -> EnrollmentResult.Failure("Unexpected response from the enrollment backend (HTTP ${response.code}).")
        }
    }

    private fun mapVerifyResponse(response: Response, credential: EnrollmentCredential): VerificationResult {
        val json = readJson(response)
        val errorCode = json?.optString("error_code").orEmpty()
        return when {
            response.code == 200 -> authorizedFrom(json, credential)
                ?: VerificationResult.Unavailable("The enrollment backend returned a malformed verification response.")
            response.code == 401 && errorCode == ERROR_INVALID_CREDENTIAL -> VerificationResult.InvalidCredential
            response.code == 403 && errorCode == ERROR_DEVICE_REVOKED -> VerificationResult.Revoked
            response.code == 404 && errorCode == ERROR_DEVICE_NOT_FOUND -> VerificationResult.UnknownDevice
            else -> VerificationResult.Unavailable("Unexpected response from the enrollment backend (HTTP ${response.code}).")
        }
    }

    private fun approvedFrom(json: JSONObject?, request: EnrollmentRequest): EnrollmentResult.Approved? {
        json ?: return null
        val deviceId = json.nonBlankString("device_id") ?: return null
        if (deviceId != request.deviceId) return null
        val token = json.nonBlankString("enrollment_credential") ?: return null
        val credential = EnrollmentCredential(
            organizationCode = json.nonBlankString("organization_code") ?: return null,
            siteCode = json.nonBlankString("site_code") ?: return null,
            deviceId = deviceId,
            token = token
        )
        if (json.optString("status") != "ACTIVE") return null
        val window = windowFrom(json) ?: return null
        return EnrollmentResult.Approved(credential, window)
    }

    private fun authorizedFrom(json: JSONObject?, credential: EnrollmentCredential): VerificationResult.Authorized? {
        json ?: return null
        if (json.opt("verified") != true) return null
        if (json.optString("device_id") != credential.deviceId) return null
        if (json.optString("status") != "ACTIVE") return null
        val window = windowFrom(json) ?: return null
        return VerificationResult.Authorized(window)
    }

    private fun windowFrom(json: JSONObject): ServerAuthorizationWindow? {
        val serverTime = json.nonBlankString("server_time")?.let(::parseIsoInstantToEpochMillis) ?: return null
        val validUntil = json.nonBlankString("offline_valid_until")?.let(::parseIsoInstantToEpochMillis) ?: return null
        if (validUntil <= serverTime) return null
        return ServerAuthorizationWindow(serverTimeEpochMs = serverTime, offlineValidUntilEpochMs = validUntil)
    }

    /** Server `detail` text, unless it would echo the submitted authorization back to the screen. */
    private fun safeDetail(json: JSONObject?, request: EnrollmentRequest): String? {
        val detail = json?.nonBlankString("detail") ?: return null
        // The server normalizes the store code, so an echo may differ in case, hyphens, or
        // spacing; compare normalized forms on both sides.
        val secret = normalizeForLeakCheck(request.enrollmentAuthorization)
        return detail.takeUnless { secret.isNotEmpty() && normalizeForLeakCheck(it).contains(secret) }
    }

    private fun normalizeForLeakCheck(value: String): String =
        value.uppercase().filterNot { it == '-' || it.isWhitespace() }

    private fun readJson(response: Response): JSONObject? =
        try {
            JSONObject(response.peekBody(MAX_RESPONSE_BYTES).string())
        } catch (e: JSONException) {
            null // malformed / non-JSON body; callers map a null body per the contract
        }

    private fun endpoint(vararg segments: String): HttpUrl? {
        val builder = baseUrl.toHttpUrlOrNull()?.newBuilder() ?: return null
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }
}

private fun JSONObject.nonBlankString(name: String): String? =
    (opt(name) as? String)?.takeIf { it.isNotBlank() }
