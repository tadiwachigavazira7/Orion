// ============================================================
// core/enrollment/DeviceAuthorizationState.kt
// Exhaustive, explicit authorization states for this installation (CLAUDE.md
// §12/§22: distinct states, never collapsed, never faked with a default).
// ============================================================
package com.orion.core.enrollment

import kotlinx.coroutines.flow.StateFlow

sealed interface DeviceAuthorizationState {

    /** Loading stored state, or a stored credential is awaiting its first verification (no valid offline allowance). */
    data object Checking : DeviceAuthorizationState

    /** Nothing stored. Enrollment requires connectivity. */
    data object NotEnrolled : DeviceAuthorizationState

    /** The platform gave no usable device identifier; enrollment is blocked. */
    data object DeviceIdUnavailable : DeviceAuthorizationState

    data object Enrolling : DeviceAuthorizationState

    /** Backend rejected the input; user may correct org/site/authorization and retry. */
    data class EnrollmentRejected(val reason: String) : DeviceAuthorizationState

    /** Enrollment did not complete (no connectivity / server error / not configured). */
    data class EnrollmentFailed(val reason: String) : DeviceAuthorizationState

    /**
     * Device already enrolled server-side, or an enrollment outcome is unknown /
     * could not be saved. Needs an administrator reset; the user may then enroll
     * again with the store's enrollment code.
     */
    data class RecoveryRequired(val reason: String) : DeviceAuthorizationState

    /** The most recent verification succeeded. Access is granted until [offlineAccessEndsAtElapsedRealtimeMs]. */
    data class Authorized(val offlineAccessEndsAtElapsedRealtimeMs: Long) : DeviceAuthorizationState

    /**
     * Access granted from a still-valid, server-issued offline allowance, without
     * a successful verification in the latest attempt (verification unavailable,
     * or not yet completed since launch). Ends exactly at
     * [offlineAccessEndsAtElapsedRealtimeMs] (monotonic clock, this boot).
     */
    data class OfflineAuthorized(
        val offlineAccessEndsAtElapsedRealtimeMs: Long,
        /** Why the latest verification did not succeed; `null` if none has completed yet. */
        val lastVerificationProblem: String?
    ) : DeviceAuthorizationState

    /** Verification unavailable and no valid offline allowance. Credential kept; a successful verify restores access. */
    data object ConnectionRequired : DeviceAuthorizationState

    /** Explicit denial: device revoked by an administrator. */
    data object Revoked : DeviceAuthorizationState

    /** Explicit denial: the stored credential is not accepted (e.g. the device was reset). */
    data object InvalidCredential : DeviceAuthorizationState

    /** Explicit denial: the backend does not know this device. */
    data object UnknownDevice : DeviceAuthorizationState
}

/** Only these two states let the employee use find/navigation. */
val DeviceAuthorizationState.grantsAccess: Boolean
    get() = this is DeviceAuthorizationState.Authorized || this is DeviceAuthorizationState.OfflineAuthorized

/**
 * Read-only seam the find/navigation flow depends on, so it can refuse to start
 * and cancel an active RFID session when access is lost.
 */
interface DeviceAccessGate {
    val state: StateFlow<DeviceAuthorizationState>

    /**
     * Fresh check against the monotonic clock, not just the last published state:
     * returns false once the offline allowance has elapsed even if the scheduled
     * expiry transition has not run yet.
     */
    fun isAccessGranted(): Boolean
}
