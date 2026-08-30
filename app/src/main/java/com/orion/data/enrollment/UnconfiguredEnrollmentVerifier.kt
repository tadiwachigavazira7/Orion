// ============================================================
// data/enrollment/UnconfiguredEnrollmentVerifier.kt
// ============================================================
package com.orion.data.enrollment

import com.orion.core.enrollment.EnrollmentRequest
import com.orion.core.enrollment.EnrollmentResult
import com.orion.core.enrollment.EnrollmentVerifier

/**
 * Intentional placeholder [EnrollmentVerifier], NOT a stub to "fill in later" silently.
 *
 * No real enterprise enrollment backend exists yet, and this codebase must never guess
 * or invent that API contract (CLAUDE.md §15, §22). This implementation always returns
 * [EnrollmentResult.Failure] and can never return [EnrollmentResult.Approved] — this is
 * what makes the missing backend dependency visible at runtime through the UI's error
 * state, instead of silently faking a successful enrollment.
 *
 * A real EnrollmentVerifier implementation must be supplied once the customer's
 * enterprise enrollment API is known (see the coding report's "Backend/service
 * dependencies" section for what that API needs to provide).
 */
class UnconfiguredEnrollmentVerifier : EnrollmentVerifier {
    override suspend fun verify(request: EnrollmentRequest): EnrollmentResult =
        EnrollmentResult.Failure(
            "No enrollment backend is configured for this build. A real EnrollmentVerifier " +
                "must be integrated with the customer's enterprise enrollment API before " +
                "production enrollment can succeed."
        )
}
