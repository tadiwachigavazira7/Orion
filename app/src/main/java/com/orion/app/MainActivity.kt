package com.orion.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orion.BuildConfig
import com.orion.core.enrollment.DeviceAccessGate
import com.orion.core.enrollment.DeviceAuthorizationState
import com.orion.core.enrollment.EnrollmentVerifier
import com.orion.core.inventory.FindInput
import com.orion.core.inventory.ResolveTargetUseCase
import com.orion.core.session.FindTagUseCase
import com.orion.data.enrollment.AndroidConnectivityMonitor
import com.orion.data.enrollment.AndroidDeviceIdProvider
import com.orion.data.enrollment.DataStoreEnrollmentStore
import com.orion.data.enrollment.HttpEnrollmentVerifier
import com.orion.data.enrollment.SettingsBootIdentityProvider
import com.orion.data.enrollment.SystemClockMonotonicClock
import com.orion.data.enrollment.UnconfiguredEnrollmentVerifier
import com.orion.integrations.fake.FakeEpcLookup
import com.orion.integrations.fake.FakeRfidReader
import kotlinx.coroutines.launch

/**
 * Minimal, hand-rolled [ViewModelProvider.Factory] — this repo has no DI framework
 * (plain constructor injection is the existing pattern), so dependencies are
 * constructed by hand here rather than pulling in a DI library.
 */
private class EnrollmentViewModelFactory(
    private val create: () -> EnrollmentViewModel
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return create() as T
    }
}

/**
 * Launch screen. Gates find/navigation on device authorization: enrollment with
 * Orion's enrollment backend, periodic online verification, and a server-issued,
 * time-limited offline allowance (see core.enrollment.DeviceAuthorizationManager).
 * No employee login/ID/session is ever involved. Android-specific implementations
 * from data.enrollment are wired here by hand, since this app has no DI framework.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val factory = EnrollmentViewModelFactory {
            // Release builds cannot be produced without a valid https ENROLLMENT_BASE_URL
            // (validated in app/build.gradle.kts); debug builds default to the emulator host.
            // UnconfiguredEnrollmentVerifier is only a defensive runtime fallback and never
            // reports success.
            val verifier: EnrollmentVerifier = BuildConfig.ENROLLMENT_BASE_URL
                .takeIf { it.isNotBlank() }
                ?.let { HttpEnrollmentVerifier(baseUrl = it) }
                ?: UnconfiguredEnrollmentVerifier()
            EnrollmentViewModel(
                store = DataStoreEnrollmentStore(applicationContext),
                verifier = verifier,
                deviceIdProvider = AndroidDeviceIdProvider(applicationContext.contentResolver),
                clock = SystemClockMonotonicClock(),
                bootIdentity = SettingsBootIdentityProvider(applicationContext.contentResolver)
            )
        }
        val enrollmentViewModel = ViewModelProvider(this, factory)[EnrollmentViewModel::class.java]

        // Verification triggers run only while the app is in the foreground: the first
        // STARTED covers app launch, later ones cover return-to-foreground; periodic and
        // connectivity-return checks are cancelled with the STOPPED transition, which also
        // unregisters the network callback.
        val connectivity = AndroidConnectivityMonitor(applicationContext)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                enrollmentViewModel.runWhileForeground(connectivity.isConnected)
            }
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = Color.White, surface = Color.White)) {
                var showSplash by rememberSaveable { mutableStateOf(true) }
                val state by enrollmentViewModel.state.collectAsState()

                if (showSplash) {
                    SplashScreen(onFinished = { showSplash = false })
                } else {
                    DeviceAuthorizationRoute(
                        state = state,
                        deviceId = enrollmentViewModel.deviceId,
                        accessGate = enrollmentViewModel.accessGate,
                        onEnroll = enrollmentViewModel::enroll,
                        onRetryVerification = enrollmentViewModel::retryVerification
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceAuthorizationRoute(
    state: DeviceAuthorizationState,
    deviceId: String?,
    accessGate: DeviceAccessGate,
    onEnroll: (String, String, String) -> Unit,
    onRetryVerification: () -> Unit
) {
    when (state) {
        DeviceAuthorizationState.Checking -> CheckingDeviceScreen()

        is DeviceAuthorizationState.Authorized,
        is DeviceAuthorizationState.OfflineAuthorized -> Column(modifier = Modifier.fillMaxSize()) {
            if (state is DeviceAuthorizationState.OfflineAuthorized) {
                OfflineAccessBanner(verificationPending = state.lastVerificationProblem == null)
            }
            Box(modifier = Modifier.weight(1f)) { FindScreen(accessGate) }
        }

        DeviceAuthorizationState.NotEnrolled,
        DeviceAuthorizationState.Enrolling,
        is DeviceAuthorizationState.EnrollmentRejected,
        is DeviceAuthorizationState.EnrollmentFailed,
        is DeviceAuthorizationState.RecoveryRequired,
        DeviceAuthorizationState.InvalidCredential,
        DeviceAuthorizationState.UnknownDevice ->
            EnrollmentScreen(state = state, deviceId = deviceId, onEnroll = onEnroll)

        DeviceAuthorizationState.ConnectionRequired -> DeviceBlockedScreen(
            title = "Verification required",
            message = "Connect to verify this device.",
            actionLabel = "Try again",
            onAction = onRetryVerification
        )

        DeviceAuthorizationState.Revoked -> DeviceBlockedScreen(
            title = "Device revoked",
            message = "This device's enrollment has been revoked. Contact an administrator.",
            actionLabel = "Check again",
            onAction = onRetryVerification
        )

        DeviceAuthorizationState.DeviceIdUnavailable -> DeviceBlockedScreen(
            title = "Device identifier unavailable",
            message = "Orion cannot read this device's identifier, so it cannot be enrolled. " +
                "Contact an administrator."
        )
    }
}

/**
 * Minimal, hand-rolled [ViewModelProvider.Factory] for [FindScreen] below —
 * same no-DI-framework pattern as [EnrollmentViewModelFactory]. The FindFlowViewModel
 * observes [DeviceAccessGate] and cancels any RFID session when access is lost.
 */
private class FindFlowViewModelFactory(
    private val resolveTarget: ResolveTargetUseCase,
    private val findTag: FindTagUseCase,
    private val accessGate: DeviceAccessGate
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return FindFlowViewModel(resolveTarget, findTag, accessGate) as T
    }
}

/** A well-formed SGTIN-96 known to [FakeEpcLookup] ("Blue Running Shoe M9"), used only to seed the emulator's fake reader/lookup pair below — not auto-submitted. */
private const val DEMO_TARGET_EPC = "30245BFB8386AA80000186A1"

/**
 * Find flow entry point.
 *
 * Wires [FindFlowViewModel] to the hardware-free [FakeRfidReader] /
 * [FakeEpcLookup] simulation pair (see integrations/fake) so the flow can be
 * exercised end-to-end on an emulator with no RFID hardware and no real
 * inventory backend: [FakeRfidReader] simulates "walking closer" to the
 * target once navigation starts. The associate drives resolution themselves
 * via [EpcEntryScreen] (typed EPC or search-by-name); nothing is auto-resolved.
 *
 * Replace [FakeRfidReader] / [FakeEpcLookup] with real
 * [com.orion.core.rfid.RfidReader] / [com.orion.core.inventory.EpcLookup]
 * implementations once they exist. [FindFlowViewModel], [EpcEntryScreen], and
 * [CompassScreen] do not need to change to support that swap.
 */
@Composable
private fun FindScreen(accessGate: DeviceAccessGate) {
    val factory = remember(accessGate) {
        FindFlowViewModelFactory(
            resolveTarget = ResolveTargetUseCase(FakeEpcLookup()),
            findTag = FindTagUseCase(FakeRfidReader(DEMO_TARGET_EPC)),
            accessGate = accessGate
        )
    }
    val viewModel: FindFlowViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()

    when (val findState = state) {
        is FindUiState.Navigating -> CompassScreen(findState.compass, findState.targetName)
        else -> EpcEntryScreen(
            state = findState,
            onResolutionScreenOpened = viewModel::onResolutionScreenOpened,
            onTypedEpc = { viewModel.onFind(FindInput.TypedEpc(it)) },
            onSearch = viewModel::onSearch,
            onEpcChosen = viewModel::onEpcChosen
        )
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
