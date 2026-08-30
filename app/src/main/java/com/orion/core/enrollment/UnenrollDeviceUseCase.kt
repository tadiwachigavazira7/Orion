// ============================================================
// core/enrollment/UnenrollDeviceUseCase.kt
// Makes device unenrollment an explicit, reachable code path.
// ============================================================
package com.orion.core.enrollment

class UnenrollDeviceUseCase(private val store: EnrollmentStore) {
    suspend fun unenroll() {
        store.clear()
    }
}
