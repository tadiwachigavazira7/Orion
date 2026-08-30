package com.orion.core.enrollment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val REQUEST = EnrollmentRequest(
    organizationCode = "ORG",
    siteCode = "SITE",
    deviceId = "device-1"
)

private val CREDENTIAL = EnrollmentCredential(
    organizationId = "org-1",
    siteId = "site-1",
    deviceId = "device-1",
    token = "opaque-token",
    issuedAt = 1L,
    expiresAt = null
)

class EnrollDeviceUseCaseTest {

    @Test
    fun `approved result is saved to the store and returned unchanged`() = runTest {
        val store = FakeEnrollmentStore()
        val verifier = FakeEnrollmentVerifier(EnrollmentResult.Approved(CREDENTIAL))
        val useCase = EnrollDeviceUseCase(verifier, store)

        val result = useCase.enroll(REQUEST)

        assertEquals(EnrollmentResult.Approved(CREDENTIAL), result)
        assertEquals(CREDENTIAL, store.saved)
    }

    @Test
    fun `rejected result leaves the store untouched`() = runTest {
        val store = FakeEnrollmentStore()
        val verifier = FakeEnrollmentVerifier(EnrollmentResult.Rejected("unknown site"))
        val useCase = EnrollDeviceUseCase(verifier, store)

        val result = useCase.enroll(REQUEST)

        assertTrue(result is EnrollmentResult.Rejected)
        assertNull(store.saved)
    }

    @Test
    fun `failure result leaves the store untouched`() = runTest {
        val store = FakeEnrollmentStore()
        val verifier = FakeEnrollmentVerifier(EnrollmentResult.Failure("not configured"))
        val useCase = EnrollDeviceUseCase(verifier, store)

        val result = useCase.enroll(REQUEST)

        assertTrue(result is EnrollmentResult.Failure)
        assertNull(store.saved)
    }

    @Test
    fun `store save failure surfaces as Failure instead of losing the approval`() = runTest {
        val throwingStore = object : EnrollmentStore {
            override suspend fun save(credential: EnrollmentCredential) {
                throw IllegalStateException("disk full")
            }
            override suspend fun load(): EnrollmentCredential? = null
            override suspend fun clear() {}
        }
        val verifier = FakeEnrollmentVerifier(EnrollmentResult.Approved(CREDENTIAL))
        val useCase = EnrollDeviceUseCase(verifier, throwingStore)

        val result = useCase.enroll(REQUEST)

        assertTrue(result is EnrollmentResult.Failure)
    }

    @Test
    fun `cancellation during store save propagates instead of becoming a Failure result`() = runTest {
        val cancellingStore = object : EnrollmentStore {
            override suspend fun save(credential: EnrollmentCredential) {
                throw CancellationException("coroutine was cancelled")
            }
            override suspend fun load(): EnrollmentCredential? = null
            override suspend fun clear() {}
        }
        val verifier = FakeEnrollmentVerifier(EnrollmentResult.Approved(CREDENTIAL))
        val useCase = EnrollDeviceUseCase(verifier, cancellingStore)

        var caught: CancellationException? = null
        try {
            useCase.enroll(REQUEST)
        } catch (e: CancellationException) {
            caught = e
        }

        assertTrue("expected CancellationException to propagate, but it was swallowed", caught != null)
    }
}
