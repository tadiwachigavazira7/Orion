package com.orion.app

import com.orion.core.enrollment.DeviceAuthorizationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentFormTest {

    @Test
    fun `enroll is enabled only when org, site and auth code all have content`() {
        val state = DeviceAuthorizationState.NotEnrolled
        assertTrue(canSubmitEnrollment(state, "ORG", "SITE", "K7Q2M-9XW4T"))

        assertFalse(canSubmitEnrollment(state, "", "SITE", "K7Q2M-9XW4T"))
        assertFalse(canSubmitEnrollment(state, "ORG", "", "K7Q2M-9XW4T"))
        assertFalse(canSubmitEnrollment(state, "ORG", "SITE", ""))
        assertFalse("whitespace-only counts as blank", canSubmitEnrollment(state, "  ", "SITE", "K7Q2M-9XW4T"))
        assertFalse(canSubmitEnrollment(state, "ORG", "\t", "K7Q2M-9XW4T"))
        assertFalse(canSubmitEnrollment(state, "ORG", "SITE", "   "))
    }

    @Test
    fun `enroll is disabled while an attempt is in flight and re-enabled after a rejection`() {
        assertFalse(canSubmitEnrollment(DeviceAuthorizationState.Enrolling, "ORG", "SITE", "CODE"))
        assertTrue(canSubmitEnrollment(DeviceAuthorizationState.EnrollmentRejected("limit"), "ORG", "SITE", "CODE"))
        assertTrue(canSubmitEnrollment(DeviceAuthorizationState.InvalidCredential, "ORG", "SITE", "CODE"))
    }
}
