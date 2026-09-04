package com.orion.core.enrollment

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

class UnenrollDeviceUseCaseTest {

    @Test
    fun `unenroll clears the store`() = runTest {
        val cred = EnrollmentCredential(
            organizationId = "org-1",
            siteId = "site-1",
            deviceId = "device-1",
            token = "opaque-token",
            issuedAt = 1L,
            expiresAt = null
        )
        val store = FakeEnrollmentStore(cred)
        val useCase = UnenrollDeviceUseCase(store)

        useCase.unenroll()

        assertNull(store.load())
    }
}
