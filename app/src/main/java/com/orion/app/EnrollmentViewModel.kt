// ============================================================
// app/EnrollmentViewModel.kt
// First-launch/subsequent-launch gate for Orion. No employee login, ID, or
// session anywhere in this flow — device-level enrollment only.
// ============================================================
package com.orion.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orion.core.enrollment.CheckEnrollmentUseCase
import com.orion.core.enrollment.DeviceIdProvider
import com.orion.core.enrollment.EnrollDeviceUseCase
import com.orion.core.enrollment.EnrollmentRequest
import com.orion.core.enrollment.EnrollmentResult
import com.orion.core.enrollment.EnrollmentStatus
import com.orion.core.enrollment.UnenrollDeviceUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface EnrollmentUiState {
    data object CheckingEnrollment : EnrollmentUiState
    data class NeedsEnrollment(val deviceId: String) : EnrollmentUiState
    data class NeedsReEnrollment(val deviceId: String, val reason: String) : EnrollmentUiState
    data object Enrolling : EnrollmentUiState
    data class EnrollmentRejected(val reason: String) : EnrollmentUiState
    data class EnrollmentFailed(val reason: String) : EnrollmentUiState
    data object Enrolled : EnrollmentUiState
}

class EnrollmentViewModel(
    private val checkEnrollment: CheckEnrollmentUseCase,
    private val enrollDevice: EnrollDeviceUseCase,
    private val unenrollDevice: UnenrollDeviceUseCase,
    private val deviceIdProvider: DeviceIdProvider
) : ViewModel() {

    private val _state = MutableStateFlow<EnrollmentUiState>(EnrollmentUiState.CheckingEnrollment)
    val state: StateFlow<EnrollmentUiState> = _state.asStateFlow()

    init {
        refreshStatus()
    }

    private fun refreshStatus() = viewModelScope.launch {
        _state.value = EnrollmentUiState.CheckingEnrollment
        when (val status = checkEnrollment.check()) {
            is EnrollmentStatus.Enrolled -> _state.value = EnrollmentUiState.Enrolled
            is EnrollmentStatus.NotEnrolled ->
                _state.value = EnrollmentUiState.NeedsEnrollment(deviceIdProvider.currentDeviceId())
            is EnrollmentStatus.Expired ->
                _state.value = EnrollmentUiState.NeedsReEnrollment(
                    deviceId = deviceIdProvider.currentDeviceId(),
                    reason = "Your device's enrollment has expired."
                )
        }
    }

    fun enroll(organizationCode: String, siteCode: String) = viewModelScope.launch {
        _state.value = EnrollmentUiState.Enrolling
        val request = EnrollmentRequest(
            organizationCode = organizationCode,
            siteCode = siteCode,
            deviceId = deviceIdProvider.currentDeviceId()
        )
        when (val result = enrollDevice.enroll(request)) {
            is EnrollmentResult.Approved -> _state.value = EnrollmentUiState.Enrolled
            is EnrollmentResult.Rejected -> _state.value = EnrollmentUiState.EnrollmentRejected(result.reason)
            is EnrollmentResult.Failure -> _state.value = EnrollmentUiState.EnrollmentFailed(result.reason)
        }
    }

    fun unenroll() = viewModelScope.launch {
        unenrollDevice.unenroll()
        _state.value = EnrollmentUiState.NeedsEnrollment(deviceIdProvider.currentDeviceId())
    }
}
