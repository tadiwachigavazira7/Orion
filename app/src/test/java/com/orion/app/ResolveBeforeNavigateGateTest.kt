package com.orion.app

import com.orion.core.inventory.FindInput
import com.orion.core.inventory.ResolveTargetUseCase
import com.orion.core.session.FindTagUseCase
import com.orion.integrations.fake.FakeEpcLookup
import com.orion.integrations.fake.FakeRfidReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val KNOWN_EPC = "30245BFB8386AA80000186A1"

/**
 * Proves the resolve-before-navigate gate behaviorally: [FindFlowViewModel.startNavigation]
 * is `private` and reachable only from a [com.orion.core.inventory.ResolveResult.Resolved]
 * branch (or an already-validated search pick) — there is no public entry point that starts
 * RFID interpretation from a raw/unresolved EPC. This test exercises the public API and
 * asserts the observable consequence of that structural gate: the UI state machine can only
 * ever reach [FindUiState.Navigating] after a successful resolution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResolveBeforeNavigateGateTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): FindFlowViewModel {
        val resolveTarget = ResolveTargetUseCase(FakeEpcLookup())
        val findTag = FindTagUseCase(FakeRfidReader(KNOWN_EPC))
        return FindFlowViewModel(resolveTarget, findTag)
    }

    @Test
    fun `unresolved epc never starts navigation`() {
        val viewModel = newViewModel()

        // well-formed SGTIN-96, but unknown to inventory
        viewModel.onFind(FindInput.TypedEpc("30245BFB8386B8C0000F423F"))

        assertTrue(viewModel.state.value is FindUiState.NotFound)
        assertTrue(viewModel.state.value !is FindUiState.Navigating)
    }

    @Test
    fun `malformed epc never starts navigation`() {
        val viewModel = newViewModel()

        viewModel.onFind(FindInput.TypedEpc("NOT-HEX!"))

        assertTrue(viewModel.state.value is FindUiState.Invalid)
        assertTrue(viewModel.state.value !is FindUiState.Navigating)
    }

    @Test
    fun `resolved epc is the only path that starts navigation`() {
        val viewModel = newViewModel()

        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))

        assertTrue(viewModel.state.value is FindUiState.Navigating)
    }
}
