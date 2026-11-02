package com.orion.data.enrollment

import com.orion.core.enrollment.EnrollmentCredential
import com.orion.core.enrollment.EnrollmentRequest
import com.orion.core.enrollment.EnrollmentResult
import com.orion.core.enrollment.ServerAuthorizationWindow
import com.orion.core.enrollment.VerificationResult
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val AUTHORIZATION_SECRET = "K7Q2M-9XW4T-STORE-CODE-SECRET"
private const val CREDENTIAL_SECRET = "plaintext-device-credential-SECRET"

private val REQUEST = EnrollmentRequest(
    organizationCode = "TEST-ORG-001",
    siteCode = "TEST-SITE-001",
    deviceId = "device-1",
    enrollmentAuthorization = AUTHORIZATION_SECRET
)

private val CREDENTIAL = EnrollmentCredential("TEST-ORG-001", "TEST-SITE-001", "device-1", CREDENTIAL_SECRET)

private val EXPECTED_WINDOW = ServerAuthorizationWindow(
    serverTimeEpochMs = java.time.Instant.parse("2026-11-02T12:00:00Z").toEpochMilli(),
    offlineValidUntilEpochMs = java.time.Instant.parse("2026-11-03T12:00:00Z").toEpochMilli()
)

private fun enrolledBody(
    deviceId: String = "device-1",
    serverTime: String? = "2026-11-02T12:00:00Z",
    validUntil: String? = "2026-11-03T12:00:00Z"
): String = JSONObject()
    .put("device_id", deviceId)
    .put("organization_code", "TEST-ORG-001")
    .put("site_code", "TEST-SITE-001")
    .put("status", "ACTIVE")
    .put("enrollment_credential", CREDENTIAL_SECRET)
    .apply {
        serverTime?.let { put("server_time", it) }
        validUntil?.let { put("offline_valid_until", it) }
    }
    .toString()

private fun verifiedBody(
    verified: Boolean = true,
    deviceId: String = "device-1",
    status: String = "ACTIVE",
    serverTime: String = "2026-11-02T12:00:00Z",
    validUntil: String = "2026-11-03T12:00:00+00:00"
): String = JSONObject()
    .put("verified", verified)
    .put("device_id", deviceId)
    .put("organization_code", "TEST-ORG-001")
    .put("site_code", "TEST-SITE-001")
    .put("status", status)
    .put("server_time", serverTime)
    .put("offline_valid_until", validUntil)
    .toString()

private fun error(detail: String, code: String) = """{"detail":"$detail","error_code":"$code"}"""

/**
 * Exercises every row of the enrollment v2 contract's Android mapping table against a
 * real (in-process) HTTP server, rather than re-implementing the mapping in the test.
 */
class HttpEnrollmentVerifierTest {

    private lateinit var server: MockWebServer
    private lateinit var verifier: HttpEnrollmentVerifier

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        verifier = HttpEnrollmentVerifier(baseUrl = server.url("/").toString().removeSuffix("/"), client = OkHttpClient())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    private fun assertNoSecrets(text: String) {
        assertFalse("leaked enrollment authorization: $text", text.contains(AUTHORIZATION_SECRET))
        assertFalse("leaked device credential: $text", text.contains(CREDENTIAL_SECRET))
    }

    // ------------------------------------------------------------------
    // POST /enrollment
    // ------------------------------------------------------------------

    @Test
    fun `enroll sends all four contract fields including the authorization`() = runTest {
        respond(201, enrolledBody())

        verifier.enroll(REQUEST)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/enrollment", recorded.target)
        val sent = JSONObject(recorded.body!!.utf8())
        assertEquals("TEST-ORG-001", sent.getString("organization_code"))
        assertEquals("TEST-SITE-001", sent.getString("site_code"))
        assertEquals("device-1", sent.getString("device_id"))
        assertEquals(AUTHORIZATION_SECRET, sent.getString("enrollment_authorization"))
        assertEquals(4, sent.length())
    }

    @Test
    fun `enroll 201 maps to Approved with credential and parsed server window`() = runTest {
        respond(201, enrolledBody())

        val result = verifier.enroll(REQUEST)

        assertEquals(EnrollmentResult.Approved(CREDENTIAL, EXPECTED_WINDOW), result)
    }

    @Test
    fun `enroll 201 that cannot be used maps to RecoveryRequired`() = runTest {
        val unusable = listOf(
            "not json",
            enrolledBody(serverTime = null),
            enrolledBody(validUntil = null),
            enrolledBody(validUntil = "2026-11-02T11:00:00Z"), // before server_time
            enrolledBody(serverTime = "yesterday"),
            enrolledBody(deviceId = "someone-else")
        )
        for (body in unusable) {
            respond(201, body)
            val result = verifier.enroll(REQUEST)
            assertTrue("body=$body result=$result", result is EnrollmentResult.RecoveryRequired)
            assertNoSecrets((result as EnrollmentResult.RecoveryRequired).reason)
        }
    }

    @Test
    fun `enroll 403 invalid_enrollment_authorization maps to Rejected with the app-owned message`() = runTest {
        respond(403, error("Enrollment authorization is invalid.", "invalid_enrollment_authorization"))

        val result = verifier.enroll(REQUEST)

        assertEquals(EnrollmentResult.Rejected(INVALID_ENROLLMENT_CODE_MESSAGE), result)
    }

    @Test
    fun `server detail echoing a normalized form of the store code is not surfaced`() = runTest {
        // Secret "K7Q2M-9XW4T-STORE-CODE-SECRET", echoed the way a normalizing server might.
        respond(404, error("Unknown site for code k7q2m 9xw4tstore-codesecret", "site_not_found"))

        val result = verifier.enroll(REQUEST)

        assertEquals(EnrollmentResult.Rejected("The organization or site code was not recognised."), result)
    }

    @Test
    fun `enroll 404 organization, site, and mismatch codes map to Rejected`() = runTest {
        for (code in listOf("organization_not_found", "site_not_found", "site_organization_mismatch")) {
            respond(404, error("Not found: $code", code))
            assertEquals(EnrollmentResult.Rejected("Not found: $code"), verifier.enroll(REQUEST))
        }
    }

    @Test
    fun `enroll 422 maps to Rejected so the user can correct input`() = runTest {
        respond(422, """{"detail":[{"loc":["body","site_code"],"msg":"field required"}]}""")

        assertTrue(verifier.enroll(REQUEST) is EnrollmentResult.Rejected)
    }

    @Test
    fun `enroll 409 device_already_enrolled maps to RecoveryRequired`() = runTest {
        respond(409, error("Device 'device-1' is already enrolled.", "device_already_enrolled"))

        assertEquals(EnrollmentResult.RecoveryRequired(ALREADY_ENROLLED_MESSAGE), verifier.enroll(REQUEST))
    }

    @Test
    fun `enroll 409 device_limit_reached is a capacity Rejected, never RecoveryRequired`() = runTest {
        respond(409, error("Device limit reached for site TEST-SITE-001 ($AUTHORIZATION_SECRET).", "device_limit_reached"))

        val result = verifier.enroll(REQUEST)

        assertEquals(EnrollmentResult.Rejected(DEVICE_LIMIT_REACHED_MESSAGE), result)
        assertEquals(
            "This store has reached its authorized device limit. Contact your store administrator.",
            (result as EnrollmentResult.Rejected).reason
        )
        assertNoSecrets(result.reason)
    }

    @Test
    fun `409 device_already_enrolled and device_limit_reached map to different outcomes`() = runTest {
        respond(409, error("Device 'device-1' is already enrolled.", "device_already_enrolled"))
        assertTrue(verifier.enroll(REQUEST) is EnrollmentResult.RecoveryRequired)
        respond(409, error("Device limit reached.", "device_limit_reached"))
        assertTrue(verifier.enroll(REQUEST) is EnrollmentResult.Rejected)
    }

    @Test
    fun `enroll 403 subscription_inactive maps to Rejected with the app-owned message`() = runTest {
        respond(403, error("Subscription for site is SUSPENDED.", "subscription_inactive"))

        val result = verifier.enroll(REQUEST)

        assertEquals(
            EnrollmentResult.Rejected("This store's Orion subscription is not active. Contact your store administrator."),
            result
        )
    }

    @Test
    fun `enroll 429 enrollment_rate_limited with Retry-After maps to a temporary Rejected`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "300")
                .body(error("Too many failed attempts for $AUTHORIZATION_SECRET.", "enrollment_rate_limited"))
                .build()
        )

        val result = verifier.enroll(REQUEST)

        assertEquals(
            EnrollmentResult.Rejected("Too many incorrect enrollment attempts. Wait a few minutes and try again."),
            result
        )
        assertNoSecrets((result as EnrollmentResult.Rejected).reason)
    }

    @Test
    fun `429 without the documented error_code is not mistaken for the rate-limit rejection`() = runTest {
        respond(429, "<html>slow down</html>")
        assertTrue(verifier.enroll(REQUEST) is EnrollmentResult.Failure)
    }

    @Test
    fun `no enrollment outcome message ever contains the store code`() = runTest {
        val responses = listOf(
            403 to error("bad code $AUTHORIZATION_SECRET", "invalid_enrollment_authorization"),
            403 to error("inactive $AUTHORIZATION_SECRET", "subscription_inactive"),
            404 to error("no org $AUTHORIZATION_SECRET", "organization_not_found"),
            409 to error("limit $AUTHORIZATION_SECRET", "device_limit_reached"),
            409 to error("enrolled $AUTHORIZATION_SECRET", "device_already_enrolled"),
            429 to error("slow $AUTHORIZATION_SECRET", "enrollment_rate_limited"),
            500 to error("boom $AUTHORIZATION_SECRET", "internal_error")
        )
        for ((code, body) in responses) {
            respond(code, body)
            val reason = when (val r = verifier.enroll(REQUEST)) {
                is EnrollmentResult.Rejected -> r.reason
                is EnrollmentResult.RecoveryRequired -> r.reason
                is EnrollmentResult.Failure -> r.reason
                is EnrollmentResult.Approved -> throw AssertionError("unexpected approval for HTTP $code")
            }
            assertNoSecrets(reason)
        }
    }

    @Test
    fun `enroll 5xx and undocumented responses map to Failure`() = runTest {
        respond(500, """{"detail":"boom"}""")
        assertTrue(verifier.enroll(REQUEST) is EnrollmentResult.Failure)
        respond(503, "")
        assertTrue(verifier.enroll(REQUEST) is EnrollmentResult.Failure)
        respond(403, error("Forbidden", "something_else"))
        assertTrue(verifier.enroll(REQUEST) is EnrollmentResult.Failure)
        respond(404, "<html>Not Found</html>")
        assertTrue(verifier.enroll(REQUEST) is EnrollmentResult.Failure)
        respond(302, "")
        assertTrue(verifier.enroll(REQUEST) is EnrollmentResult.Failure)
    }

    @Test
    fun `enroll against an unreachable server maps to Failure`() = runTest {
        server.close()

        val result = verifier.enroll(REQUEST)

        assertTrue(result is EnrollmentResult.Failure)
        assertNoSecrets((result as EnrollmentResult.Failure).reason)
    }

    @Test
    fun `enroll response lost after the request was sent maps to RecoveryRequired, without retrying`() = runTest {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        respond(201, enrolledBody()) // must NOT be consumed by a silent retry

        val result = verifier.enroll(REQUEST)

        assertTrue("result=$result", result is EnrollmentResult.RecoveryRequired)
        assertEquals("the enrollment request was sent exactly once (no silent resend)", 1, server.requestCount)
    }

    @Test
    fun `server detail that echoes the authorization is not surfaced`() = runTest {
        respond(403, error("Authorization $AUTHORIZATION_SECRET is invalid.", "invalid_enrollment_authorization"))

        val result = verifier.enroll(REQUEST) as EnrollmentResult.Rejected

        assertNoSecrets(result.reason)
    }

    @Test
    fun `request toString redacts the authorization`() {
        assertNoSecrets(REQUEST.toString())
        assertNoSecrets(CREDENTIAL.toString())
    }

    // ------------------------------------------------------------------
    // POST /enrolled_devices/{device_id}/verify
    // ------------------------------------------------------------------

    @Test
    fun `verify posts the credential to the device's verify endpoint`() = runTest {
        respond(200, verifiedBody())

        verifier.verify(CREDENTIAL)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/enrolled_devices/device-1/verify", recorded.target)
        val sent = JSONObject(recorded.body!!.utf8())
        assertEquals(CREDENTIAL_SECRET, sent.getString("enrollment_credential"))
        assertEquals(1, sent.length())
    }

    @Test
    fun `verify path-encodes the device id`() = runTest {
        respond(200, verifiedBody(deviceId = "a/b c"))

        verifier.verify(CREDENTIAL.copy(deviceId = "a/b c"))

        assertEquals("/enrolled_devices/a%2Fb%20c/verify", server.takeRequest().target)
    }

    @Test
    fun `verify 200 maps to Authorized with parsed server_time and offline_valid_until`() = runTest {
        respond(200, verifiedBody())

        assertEquals(VerificationResult.Authorized(EXPECTED_WINDOW), verifier.verify(CREDENTIAL))
    }

    @Test
    fun `backend timestamps with microseconds and Z suffix are parsed on enroll and verify`() = runTest {
        // Exact shape the FastAPI backend emits.
        val serverTime = "2026-11-02T18:00:51.362197Z"
        val validUntil = "2026-11-03T18:00:51.362197Z"
        val expected = ServerAuthorizationWindow(
            serverTimeEpochMs = java.time.Instant.parse(serverTime).toEpochMilli(),
            offlineValidUntilEpochMs = java.time.Instant.parse(validUntil).toEpochMilli()
        )

        respond(201, enrolledBody(serverTime = serverTime, validUntil = validUntil))
        assertEquals(EnrollmentResult.Approved(CREDENTIAL, expected), verifier.enroll(REQUEST))

        respond(200, verifiedBody(serverTime = serverTime, validUntil = validUntil))
        val verified = verifier.verify(CREDENTIAL) as VerificationResult.Authorized
        assertEquals(expected, verified.window)
        assertEquals(24 * 60 * 60 * 1000L, verified.window.durationMs)
    }

    @Test
    fun `lost-response replay that the backend answers with 409 is RecoveryRequired`() = runTest {
        // The backend checks device state before the store code, so replaying the enrollment
        // for a device that is now ACTIVE returns 409 rather than a code/capacity error.
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        assertTrue(verifier.enroll(REQUEST) is EnrollmentResult.RecoveryRequired)

        respond(409, error("Device 'device-1' is already enrolled.", "device_already_enrolled"))
        assertEquals(EnrollmentResult.RecoveryRequired(ALREADY_ENROLLED_MESSAGE), verifier.enroll(REQUEST))
    }

    @Test
    fun `verify 200 that is malformed or inconsistent maps to Unavailable`() = runTest {
        val bad = listOf(
            "not json",
            verifiedBody(verified = false),
            verifiedBody(deviceId = "device-2"),
            verifiedBody(status = "REVOKED"),
            verifiedBody(validUntil = "2026-11-02T12:00:00Z"), // zero-length window
            verifiedBody(serverTime = "2026-11-02T12:00:00") // no zone
        )
        for (body in bad) {
            respond(200, body)
            val result = verifier.verify(CREDENTIAL)
            assertTrue("body=$body result=$result", result is VerificationResult.Unavailable)
        }
    }

    @Test
    fun `verify 401 invalid_credential maps to InvalidCredential`() = runTest {
        respond(401, error("Invalid credential.", "invalid_credential"))
        assertEquals(VerificationResult.InvalidCredential, verifier.verify(CREDENTIAL))
    }

    @Test
    fun `verify 403 device_revoked maps to Revoked`() = runTest {
        respond(403, error("Device revoked.", "device_revoked"))
        assertEquals(VerificationResult.Revoked, verifier.verify(CREDENTIAL))
    }

    @Test
    fun `verify 404 device_not_found maps to UnknownDevice`() = runTest {
        respond(404, error("Device not found.", "device_not_found"))
        assertEquals(VerificationResult.UnknownDevice, verifier.verify(CREDENTIAL))
    }

    @Test
    fun `verify denial statuses without the documented error_code are Unavailable, not denials`() = runTest {
        for ((code, body) in listOf(
            401 to "<html>proxy login</html>",
            403 to error("Forbidden", "captive_portal"),
            404 to "<html>Not Found</html>",
            404 to error("Not found", "organization_not_found")
        )) {
            respond(code, body)
            val result = verifier.verify(CREDENTIAL)
            assertTrue("HTTP $code -> $result", result is VerificationResult.Unavailable)
        }
    }

    @Test
    fun `verify 5xx, redirects, and other statuses map to Unavailable`() = runTest {
        for (code in listOf(500, 502, 503, 409, 422, 302)) {
            server.enqueue(
                MockResponse.Builder().code(code).addHeader("Location", "http://evil.example/").body("").build()
            )
            assertTrue("HTTP $code", verifier.verify(CREDENTIAL) is VerificationResult.Unavailable)
        }
        assertEquals("redirect was not followed", 6, server.requestCount)
    }

    @Test
    fun `verify against an unreachable server maps to Unavailable without leaking the credential`() = runTest {
        server.close()

        val result = verifier.verify(CREDENTIAL)

        assertTrue(result is VerificationResult.Unavailable)
        assertNoSecrets((result as VerificationResult.Unavailable).reason)
    }

    @Test
    fun `invalid base URL is reported, never a denial or success`() = runTest {
        val broken = HttpEnrollmentVerifier(baseUrl = "not a url")
        assertTrue(broken.verify(CREDENTIAL) is VerificationResult.Unavailable)
        assertTrue(broken.enroll(REQUEST) is EnrollmentResult.Failure)
    }
}
