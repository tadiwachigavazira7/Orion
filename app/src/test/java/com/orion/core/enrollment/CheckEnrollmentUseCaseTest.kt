package com.orion.core.enrollment

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIXED_NOW = 1_000_000L

private fun credential(expiresAt: Long?) = EnrollmentCredential(
    organizationId = "org-1",
    siteId = "site-1",
    deviceId = "device-1",
    token = "opaque-token",
    issuedAt = FIXED_NOW - 1_000L,
    expiresAt = expiresAt
)

class CheckEnrollmentUseCaseTest {

    private val fixedNow: () -> Long = { FIXED_NOW }

    @Test
    fun `no stored credential is NotEnrolled`() = runTest {
        val useCase = CheckEnrollmentUseCase(FakeEnrollmentStore(null), fixedNow)

        assertEquals(EnrollmentStatus.NotEnrolled, useCase.check())
    }

    @Test
    fun `credential with null expiry never expires`() = runTest {
        val cred = credential(expiresAt = null)
        val useCase = CheckEnrollmentUseCase(FakeEnrollmentStore(cred), fixedNow)

        val status = useCase.check()

        assertTrue(status is EnrollmentStatus.Enrolled)
        assertEquals(cred, (status as EnrollmentStatus.Enrolled).credential)
    }

    @Test
    fun `credential expiring in the future is Enrolled`() = runTest {
        val cred = credential(expiresAt = FIXED_NOW + 1_000L)
        val useCase = CheckEnrollmentUseCase(FakeEnrollmentStore(cred), fixedNow)

        assertTrue(useCase.check() is EnrollmentStatus.Enrolled)
    }

    @Test
    fun `credential expired in the past is Expired`() = runTest {
        val cred = credential(expiresAt = FIXED_NOW - 1L)
        val useCase = CheckEnrollmentUseCase(FakeEnrollmentStore(cred), fixedNow)

        val status = useCase.check()

        assertTrue(status is EnrollmentStatus.Expired)
        assertEquals(cred, (status as EnrollmentStatus.Expired).credential)
    }
}
