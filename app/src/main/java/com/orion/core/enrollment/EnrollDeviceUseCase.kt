// ============================================================
// core/enrollment/EnrollDeviceUseCase.kt
// First-launch enrollment: verify with the backend, then persist locally
// only on approval. Rejected/Failure never touch the store.
// ============================================================
package com.orion.core.enrollment

import kotlinx.coroutines.CancellationException

class EnrollDeviceUseCase(
    private val verifier: EnrollmentVerifier,
    private val store: EnrollmentStore
) {
    suspend fun enroll(request: EnrollmentRequest): EnrollmentResult {
        return when (val result = verifier.verify(request)) {
            is EnrollmentResult.Approved -> {
                try {
                    store.save(result.credential)
                    result
                } catch (e: CancellationException) {
                    // Never swallow cancellation — a cancelled coroutine must not keep
                    // running and produce a result as if it completed normally.
                    throw e
                } catch (e: Exception) {
                    // Persistence failed after a genuine backend approval — surface this
                    // explicitly rather than reporting Approved when the credential was
                    // never actually saved (would silently fail the next launch's check).
                    EnrollmentResult.Failure("Enrollment was approved but could not be saved locally: ${e.message}")
                }
            }
            is EnrollmentResult.Rejected -> result
            is EnrollmentResult.Failure -> result
        }
    }
}
