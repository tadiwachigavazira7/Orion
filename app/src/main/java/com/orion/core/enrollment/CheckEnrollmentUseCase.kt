// ============================================================
// core/enrollment/CheckEnrollmentUseCase.kt
// LOCAL ONLY status check — never makes a network call. Subsequent-launch
// gating compares a locally-stored expiry timestamp against the current
// time (CLAUDE.md-style local-first design; see product requirement).
// ============================================================
package com.orion.core.enrollment

class CheckEnrollmentUseCase(
    private val store: EnrollmentStore,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun check(): EnrollmentStatus {
        val credential = store.load() ?: return EnrollmentStatus.NotEnrolled
        val expiresAt = credential.expiresAt
        return if (expiresAt == null || expiresAt > now()) {
            EnrollmentStatus.Enrolled(credential)
        } else {
            EnrollmentStatus.Expired(credential)
        }
    }
}
