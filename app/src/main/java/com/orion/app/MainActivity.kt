package com.orion.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orion.BuildConfig
import com.orion.core.enrollment.CheckEnrollmentUseCase
import com.orion.core.enrollment.DeviceIdProvider
import com.orion.core.enrollment.EnrollDeviceUseCase
import com.orion.core.enrollment.EnrollmentVerifier
import com.orion.core.enrollment.UnenrollDeviceUseCase
import com.orion.core.inventory.FindInput
import com.orion.core.inventory.ResolveTargetUseCase
import com.orion.core.session.FindTagUseCase
import com.orion.data.enrollment.AndroidDeviceIdProvider
import com.orion.data.enrollment.DataStoreEnrollmentStore
import com.orion.data.enrollment.HttpEnrollmentVerifier
import com.orion.data.enrollment.UnconfiguredEnrollmentVerifier
import com.orion.integrations.fake.FakeEpcLookup

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
        // Real backend when BuildConfig.ENROLLMENT_BASE_URL is configured (debug/emulator
        // builds by default; see app/build.gradle.kts), otherwise the honest placeholder --
        // release builds ship an empty ENROLLMENT_BASE_URL until a real backend is wired up
        // for that build type, rather than baking in a local dev address.
        val verifier: EnrollmentVerifier = BuildConfig.ENROLLMENT_BASE_URL
            .takeIf { it.isNotBlank() }
            ?.let { HttpEnrollmentVerifier(baseUrl = it) }
            ?: UnconfiguredEnrollmentVerifier()
        val deviceIdProvider = AndroidDeviceIdProvider(contentResolver)
        val factory = EnrollmentViewModelFactory(
            checkEnrollment = CheckEnrollmentUseCase(store),
            enrollDevice = EnrollDeviceUseCase(verifier, store),
            unenrollDevice = UnenrollDeviceUseCase(store),
            deviceIdProvider = deviceIdProvider
        )

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = Color.White, surface = Color.White)) {
                var showSplash by rememberSaveable { mutableStateOf(true) }
                val viewModel: EnrollmentViewModel = viewModel(factory = factory)
                val state by viewModel.state.collectAsState()

                if (showSplash) {
                    SplashScreen(onFinished = { showSplash = false })
                } else {
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
                        is EnrollmentUiState.Enrolled -> FindScreen()
                    }
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
 * Minimal, hand-rolled [ViewModelProvider.Factory] for [FindScreen] below —
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

/**
 * Find flow entry point.
 *
 * Wires [FindFlowViewModel] to [provideRfidReader] (variant-specific: a debug-only mock
 * reader in src/debug, an honest "unconfigured" reader in src/release) and
 * [FakeEpcLookup], so the flow runs end-to-end on an emulator with no RFID hardware and
 * no real inventory backend. The associate drives resolution themselves
 * via [EpcEntryScreen] (typed EPC or search-by-name); nothing is auto-resolved.
 *
 * Replace [FakeEpcLookup] and the release [provideRfidReader] with real
 * [com.orion.core.inventory.EpcLookup] / [com.orion.core.rfid.RfidReader]
 * implementations once they exist. [FindFlowViewModel], [EpcEntryScreen], and
 * [CompassScreen] do not need to change to support that swap.
 */
@Composable
private fun FindScreen() {
    val factory = remember {
        FindFlowViewModelFactory(
            resolveTarget = ResolveTargetUseCase(FakeEpcLookup()),
            findTag = FindTagUseCase(provideRfidReader())
        )
    }
    val viewModel: FindFlowViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()

    when (val findState = state) {
        is FindUiState.Navigating -> {
            // System back returns to a fresh EPC entry screen; on EpcEntryScreen itself
            // there is no handler, so back keeps its default (exit app).
            BackHandler(onBack = viewModel::back)
            CompassScreen(findState.compass, findState.targetName, onBack = viewModel::back)
        }
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
