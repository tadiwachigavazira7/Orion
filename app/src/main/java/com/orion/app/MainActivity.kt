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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orion.core.enrollment.CheckEnrollmentUseCase
import com.orion.core.enrollment.DeviceIdProvider
import com.orion.core.enrollment.EnrollDeviceUseCase
import com.orion.core.enrollment.UnenrollDeviceUseCase
import com.orion.core.inventory.FindInput
import com.orion.core.inventory.ResolveTargetUseCase
import com.orion.core.session.FindTagUseCase
import com.orion.data.enrollment.AndroidDeviceIdProvider
import com.orion.data.enrollment.DataStoreEnrollmentStore
import com.orion.data.enrollment.UnconfiguredEnrollmentVerifier
import com.orion.integrations.fake.FakeEpcLookup
import com.orion.integrations.fake.FakeRfidReader

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

        val store = DataStoreEnrollmentStore(applicationContext)
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
                    is EnrollmentUiState.Enrolled -> FindDemoScreen()
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

/**
 * Minimal, hand-rolled [ViewModelProvider.Factory] for [FindDemoScreen] below —
 * same no-DI-framework pattern as [EnrollmentViewModelFactory].
 */
private class FindFlowViewModelFactory(
    private val resolveTarget: ResolveTargetUseCase,
    private val findTag: FindTagUseCase
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return FindFlowViewModel(resolveTarget, findTag) as T
    }
}

/** A well-formed SGTIN-96 known to [FakeEpcLookup] ("Blue Running Shoe M9"). */
private const val DEMO_TARGET_EPC = "30245BFB8386AA80000186A1"

/**
 * TEMPORARY EMULATOR DEMO — NOT the production find flow.
 *
 * Wires [FindFlowViewModel] to the hardware-free [FakeRfidReader] /
 * [FakeEpcLookup] simulation pair (see integrations/fake) so [CompassScreen]
 * can be exercised end-to-end on an emulator with no RFID hardware and no
 * real inventory backend: [FakeRfidReader] simulates "walking closer" to the
 * target over a few seconds. On first composition it immediately resolves
 * and starts navigating to [DEMO_TARGET_EPC] — there is no EPC-entry UI yet.
 *
 * Replace this with a real EPC-entry screen (scan / type / search) wired to
 * a real [com.orion.core.rfid.RfidReader] / [com.orion.core.inventory.EpcLookup]
 * pair once one exists. [FindFlowViewModel] and [CompassScreen] do not need
 * to change to support that swap.
 */
@Composable
private fun FindDemoScreen() {
    val factory = remember {
        FindFlowViewModelFactory(
            resolveTarget = ResolveTargetUseCase(FakeEpcLookup()),
            findTag = FindTagUseCase(FakeRfidReader(DEMO_TARGET_EPC))
        )
    }
    val viewModel: FindFlowViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.onFind(FindInput.TypedEpc(DEMO_TARGET_EPC))
    }

    when (val findState = state) {
        is FindUiState.Navigating -> CompassScreen(findState.compass, findState.targetName)
        // Genuinely still in progress — a loading spinner is honest here.
        is FindUiState.Idle,
        is FindUiState.Resolving,
        is FindUiState.PickEpc -> CheckingEnrollmentScreen()
        // Real, terminal failures — must NOT be indistinguishable from "still loading"
        // (CLAUDE.md §12/§22).
        is FindUiState.NotFound -> DemoResolutionFailureScreen("EPC not found: ${findState.epc}")
        is FindUiState.Invalid -> DemoResolutionFailureScreen("Invalid EPC: ${findState.reason}")
        is FindUiState.Error -> DemoResolutionFailureScreen("Lookup failed: ${findState.message}")
    }
}

/** Distinct, non-loading rendering for a demo resolution failure (NotFound / Invalid / Error). */
@Composable
private fun DemoResolutionFailureScreen(message: String) {
    Scaffold { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
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
