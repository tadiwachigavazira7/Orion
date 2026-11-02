// ============================================================
// data/enrollment/UnconfiguredEnrollmentVerifier.kt
// ============================================================
package com.orion.data.enrollment

import com.orion.core.enrollment.EnrollmentCredential
import com.orion.core.enrollment.EnrollmentRequest
import com.orion.core.enrollment.EnrollmentResult
import com.orion.core.enrollment.EnrollmentVerifier
import com.orion.core.enrollment.VerificationResult

/**
 * Defensive runtime fallback used only when a build has no usable
 * `BuildConfig.ENROLLMENT_BASE_URL` (see MainActivity). Release builds cannot be
 * produced without a valid https URL (app/build.gradle.kts validation), so in
 * practice this guards misconfigured custom/debug builds.
 *
 * It can never fake success: enrollment always returns [EnrollmentResult.Failure]
 * and verification always returns [VerificationResult.Unavailable] (NOT a denial,
 * so a correctly enrolled device keeps only its server-issued offline allowance).
 */
class UnconfiguredEnrollmentVerifier : EnrollmentVerifier {
    override suspend fun enroll(request: EnrollmentRequest): EnrollmentResult =
        EnrollmentResult.Failure(NOT_CONFIGURED)

    override suspend fun verify(credential: EnrollmentCredential): VerificationResult =
        VerificationResult.Unavailable(NOT_CONFIGURED)

    private companion object {
        const val NOT_CONFIGURED =
            "No enrollment backend URL is configured for this build of Orion."
    }
}
