package com.orion.data.enrollment

import com.orion.core.enrollment.EnrollmentRequest
import com.orion.core.enrollment.EnrollmentResult
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val REQUEST = EnrollmentRequest(
    organizationCode = "TEST-ORG-001",
    siteCode = "TEST-SITE-001",
    deviceId = "device-1"
)

/**
 * Exercises [HttpEnrollmentVerifier]'s HTTP-status-code -> [EnrollmentResult] mapping
 * against a real (local, in-process) HTTP server via OkHttp's MockWebServer, rather than
 * re-implementing the mapping logic by hand in the test -- this is the behavior most likely
 * to be subtly wrong (e.g. which codes are Rejected vs Failure) and is exactly what a real
 * request/response round-trip should cover.
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

    @Test
    fun `201 response maps to Approved with the documented field mapping`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .body(
                    """
                    {
                        "device_id": "device-1",
                        "organization_code": "TEST-ORG-001",
                        "site_code": "TEST-SITE-001",
                        "status": "ACTIVE",
                        "enrollment_credential": "opaque-secret"
                    }
                    """.trimIndent()
                )
                .build()
        )

        val result = verifier.verify(REQUEST)

        assertTrue(result is EnrollmentResult.Approved)
        val credential = (result as EnrollmentResult.Approved).credential
        assertEquals("TEST-ORG-001", credential.organizationId)
        assertEquals("TEST-SITE-001", credential.siteId)
        assertEquals("device-1", credential.deviceId)
        assertEquals("opaque-secret", credential.token)
        assertNull(credential.expiresAt)
    }

    @Test
    fun `sends the documented request body shape`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .body(
                    """{"device_id":"device-1","organization_code":"TEST-ORG-001","site_code":"TEST-SITE-001","status":"ACTIVE","enrollment_credential":"x"}"""
                )
                .build()
        )

        verifier.verify(REQUEST)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/enrollment", recorded.target)
        val sentBody = recorded.body?.utf8() ?: ""
        assertTrue(sentBody.contains("\"organization_code\":\"TEST-ORG-001\""))
        assertTrue(sentBody.contains("\"site_code\":\"TEST-SITE-001\""))
        assertTrue(sentBody.contains("\"device_id\":\"device-1\""))
    }

    @Test
    fun `404 organization_not_found maps to Rejected with the backend's detail message`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .body("""{"detail":"Organization 'BAD-ORG' was not found.","error_code":"organization_not_found"}""")
                .build()
        )

        val result = verifier.verify(REQUEST)

        assertTrue(result is EnrollmentResult.Rejected)
        assertEquals("Organization 'BAD-ORG' was not found.", (result as EnrollmentResult.Rejected).reason)
    }

    @Test
    fun `409 device_already_enrolled maps to Rejected`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(409)
                .body("""{"detail":"Device 'device-1' is already enrolled.","error_code":"device_already_enrolled"}""")
                .build()
        )

        val result = verifier.verify(REQUEST)

        assertTrue(result is EnrollmentResult.Rejected)
        assertEquals("Device 'device-1' is already enrolled.", (result as EnrollmentResult.Rejected).reason)
    }

    @Test
    fun `unexpected status code maps to Failure, not Rejected`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("""{"detail":"boom"}""").build())

        val result = verifier.verify(REQUEST)

        assertTrue(result is EnrollmentResult.Failure)
    }

    @Test
    fun `malformed 201 body maps to Failure rather than throwing`() = runTest {
        server.enqueue(MockResponse.Builder().code(201).body("not json").build())

        val result = verifier.verify(REQUEST)

        assertTrue(result is EnrollmentResult.Failure)
    }

    @Test
    fun `rejection with unparsable body still produces a usable Rejected reason`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("not json").build())

        val result = verifier.verify(REQUEST)

        assertTrue(result is EnrollmentResult.Rejected)
        assertTrue((result as EnrollmentResult.Rejected).reason.isNotBlank())
    }

    @Test
    fun `unreachable server maps to Failure`() = runTest {
        server.close()

        val result = verifier.verify(REQUEST)

        assertTrue(result is EnrollmentResult.Failure)
    }
}
