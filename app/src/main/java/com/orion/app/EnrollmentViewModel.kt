// ============================================================
// app/EnrollmentViewModel.kt
// Owns the DeviceAuthorizationManager for the Activity's lifetime (survives
// configuration changes) and exposes its state to Compose. No employee
// login, ID, or session anywhere in this flow -- device-level only.
// ============================================================
package com.orion.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orion.core.enrollment.BootIdentityProvider
import com.orion.core.enrollment.DeviceAccessGate
import com.orion.core.enrollment.DeviceAuthorizationManager
import com.orion.core.enrollment.DeviceAuthorizationState
import com.orion.core.enrollment.DeviceIdProvider
import com.orion.core.enrollment.EnrollmentStore
import com.orion.core.enrollment.EnrollmentVerifier
import com.orion.core.enrollment.MonotonicClock
import com.orion.core.enrollment.VerificationSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class EnrollmentViewModel(
    store: EnrollmentStore,
    verifier: EnrollmentVerifier,
    deviceIdProvider: DeviceIdProvider,
    clock: MonotonicClock,
    bootIdentity: BootIdentityProvider,
    schedule: VerificationSchedule = VerificationSchedule()
) : ViewModel() {

    private val manager = DeviceAuthorizationManager(
        store = store,
        verifier = verifier,
        deviceIdProvider = deviceIdProvider,
        clock = clock,
        bootIdentity = bootIdentity,
        scope = viewModelScope,
        schedule = schedule
    )

    /** Read-only gate for the find/navigation flow. */
    val accessGate: DeviceAccessGate get() = manager

    val state: StateFlow<DeviceAuthorizationState> = manager.state

    /** Shown on the enrollment form; `null` when the platform provides no identifier. */
    val deviceId: String? = deviceIdProvider.currentDeviceId()

    fun enroll(organizationCode: String, siteCode: String, enrollmentAuthorization: String) {
        viewModelScope.launch { manager.enroll(organizationCode, siteCode, enrollmentAuthorization) }
    }

    fun retryVerification() = manager.requestVerification()

    /**
     * Foreground triggers (launch/return-to-foreground, periodic, connectivity
     * return). Must be called from a lifecycle-bound collector such as
     * `repeatOnLifecycle(Lifecycle.State.STARTED)`; cancelled when the app stops.
     */
    suspend fun runWhileForeground(connectivity: Flow<Boolean>) = manager.runWhileForeground(connectivity)
}
