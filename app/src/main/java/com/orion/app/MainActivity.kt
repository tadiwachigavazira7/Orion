package com.orion.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orion.core.enrollment.CheckEnrollmentUseCase
import com.orion.core.enrollment.DeviceIdProvider
import com.orion.core.enrollment.EnrollDeviceUseCase
import com.orion.core.enrollment.UnenrollDeviceUseCase
import com.orion.data.enrollment.AndroidDeviceIdProvider
import com.orion.data.enrollment.EncryptedPrefsEnrollmentStore
import com.orion.data.enrollment.UnconfiguredEnrollmentVerifier

/**
 * Minimal, hand-rolled [ViewModelProvider.Factory] — this repo has no DI framework
 * (plain constructor injection is the existing pattern), so dependencies are
 * constructed by hand here rather than pulling in a DI library for one ViewModel.
 */
private class EnrollmentViewModelFactory(
    private val checkEnrollment: CheckEnrollmentUseCase,
    private val enrollDevice: EnrollDeviceUseCase,
    private val unenrollDevice: UnenrollDeviceUseCase,
    private val deviceIdProvider: DeviceIdProvider
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return EnrollmentViewModel(checkEnrollment, enrollDevice, unenrollDevice, deviceIdProvider) as T
    }
}

/**
 * Launch screen. Gates access to Orion on local device enrollment state
 * (CLAUDE.md-style hardware-agnostic entry point) — no employee login/ID/
 * session is ever involved in this gate. See core.enrollment for the domain
 * model and data.enrollment for the Android-specific implementations wired
 * here by hand, since this app has no DI framework.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val store = EncryptedPrefsEnrollmentStore(applicationContext)
        val verifier = UnconfiguredEnrollmentVerifier()
        val deviceIdProvider = AndroidDeviceIdProvider(contentResolver)
        val factory = EnrollmentViewModelFactory(
            checkEnrollment = CheckEnrollmentUseCase(store),
            enrollDevice = EnrollDeviceUseCase(verifier, store),
            unenrollDevice = UnenrollDeviceUseCase(store),
            deviceIdProvider = deviceIdProvider
        )

        setContent {
            MaterialTheme {
                val viewModel: EnrollmentViewModel = viewModel(factory = factory)
                val state by viewModel.state.collectAsState()

                when (val enrollmentState = state) {
                    is EnrollmentUiState.CheckingEnrollment -> CheckingEnrollmentScreen()
                    is EnrollmentUiState.NeedsEnrollment,
                    is EnrollmentUiState.NeedsReEnrollment,
                    is EnrollmentUiState.Enrolling,
                    is EnrollmentUiState.EnrollmentRejected,
                    is EnrollmentUiState.EnrollmentFailed ->
                        EnrollmentScreen(
                            state = enrollmentState,
                            onEnroll = { orgCode, siteCode -> viewModel.enroll(orgCode, siteCode) }
                        )
                    is EnrollmentUiState.Enrolled -> OrionPlaceholderScreen()
                }
            }
        }
    }
}

@Composable
private fun CheckingEnrollmentScreen() {
    Scaffold { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun OrionPlaceholderScreen() {
    Scaffold { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Orion")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OrionPlaceholderScreenPreview() {
    MaterialTheme {
        OrionPlaceholderScreen()
    }
}
