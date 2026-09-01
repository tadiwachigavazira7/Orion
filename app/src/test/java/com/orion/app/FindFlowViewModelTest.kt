package com.orion.app

import com.orion.core.inventory.EpcLookup
import com.orion.core.inventory.EpcTarget
import com.orion.core.inventory.FindInput
import com.orion.core.inventory.ResolveResult
import com.orion.core.inventory.ResolveTargetUseCase
import com.orion.core.rfid.RfidObservation
import com.orion.core.rfid.RfidReader
import com.orion.core.session.FindTagUseCase
import com.orion.integrations.fake.FakeEpcLookup
import com.orion.integrations.fake.FakeRfidReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val KNOWN_EPC = "30245BFB8386AA80000186A1"
private const val KNOWN_DISPLAY_NAME = "Blue Running Shoe M9"

/**
 * Always fails to connect — exercises FindTagUseCase's/onStart's error path without
 * touching FakeRfidReader's "walking closer" simulation.
 */
private class FailingRfidReader : RfidReader {
    override val observations: Flow<RfidObservation> = emptyFlow()
    override suspend fun connect() = Result.failure<Unit>(IllegalStateException("reader unavailable"))
    override suspend fun startInventory() = Result.success(Unit)
    override suspend fun stopInventory() = Result.success(Unit)
    override suspend fun disconnect() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class FindFlowViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `resolved epc reaches Navigating carrying the fake lookup's display name`() {
        val viewModel = FindFlowViewModel(
            ResolveTargetUseCase(FakeEpcLookup()),
            FindTagUseCase(FakeRfidReader(KNOWN_EPC))
        )

        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))

        val state = viewModel.state.value
        assertTrue("expected Navigating, was $state", state is FindUiState.Navigating)
        assertEquals(KNOWN_DISPLAY_NAME, (state as FindUiState.Navigating).targetName)
    }

    @Test
    fun `resolved epc observably reaches Guiding, not just Searching, under an eager test dispatcher`() {
        // UnconfinedTestDispatcher runs coroutines eagerly: by the time onFind() returns
        // control, startNavigation's collector has already run synchronously through
        // FakeRfidReader's first emission (it emits before its first delay(), so no virtual
        // time needs to be advanced). That makes Searching itself unobservable via a single
        // `.value` peek in this test setup — it is set synchronously but immediately
        // overwritten before this assertion ever runs. What IS observable and asserted here
        // is that a real NavigationState made it through the pipeline and out the other side
        // as Guiding. Searching's actual reachability is verified separately below, using a
        // non-eager dispatcher with an active collector.
        val viewModel = FindFlowViewModel(
            ResolveTargetUseCase(FakeEpcLookup()),
            FindTagUseCase(FakeRfidReader(KNOWN_EPC))
        )

        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))

        val navigating = viewModel.state.value as FindUiState.Navigating
        assertTrue(
            "expected Guiding, was ${navigating.compass}",
            navigating.compass is CompassUiState.Guiding
        )
    }

    @Test
    fun `reader that fails to connect surfaces as a Navigating CompassUiState Error, not a top-level Error`() {
        // This failure happens AFTER the compass screen mounts (post-resolution), so it must
        // stay inside Navigating/CompassUiState.Error with target context, distinct from the
        // pre-navigation FindUiState.Error used for resolution/lookup failures.
        val viewModel = FindFlowViewModel(
            ResolveTargetUseCase(FakeEpcLookup()),
            FindTagUseCase(FailingRfidReader())
        )

        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))

        val state = viewModel.state.value
        assertTrue("expected Navigating, was $state", state is FindUiState.Navigating)
        val navigating = state as FindUiState.Navigating
        assertEquals(KNOWN_DISPLAY_NAME, navigating.targetName)
        assertTrue(
            "expected CompassUiState.Error, was ${navigating.compass}",
            navigating.compass is CompassUiState.Error
        )
    }

    @Test
    fun `resolution failure before navigation starts still surfaces as top-level Error`() {
        // A distinct, earlier failure moment: EPC lookup itself failed, before any target
        // was resolved and before the compass screen ever mounts — no target/compass
        // context exists yet, so this legitimately stays a top-level FindUiState.Error.
        val failingLookup = object : EpcLookup {
            override suspend fun validate(epc: String) = ResolveResult.Failure("lookup unavailable")
            override suspend fun searchEpcs(query: String) = emptyList<EpcTarget>()
        }
        val viewModel = FindFlowViewModel(
            ResolveTargetUseCase(failingLookup),
            FindTagUseCase(FakeRfidReader(KNOWN_EPC))
        )

        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))

        assertTrue(viewModel.state.value is FindUiState.Error)
    }

    @Test
    fun `Searching is actually observed before the first real compass state under a non-eager dispatcher`() {
        // UnconfinedTestDispatcher (used by the other tests here) runs everything synchronously,
        // so peeking viewModel.state.value after onFind() only ever shows the final settled
        // state — it cannot prove Searching was genuinely emitted along the way. This test uses
        // its own StandardTestDispatcher and an active collector subscribed *before* onFind()
        // is called, so the actual emission sequence is captured.
        //
        // A manually-created scope + explicit, bounded scheduler advancement is used here
        // instead of runTest{}/advanceUntilIdle(): FakeRfidReader's observation flow is an
        // infinite `while (true) { emit(...); delay(200) }` loop fed by viewModelScope (which
        // is not a structural child of any test scope and is never torn down here), so a
        // scheduler-wide "drain everything" call would spin forever. A bounded advance is
        // enough to observe several emissions past the first.
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val collectorScope = CoroutineScope(dispatcher)

        val viewModel = FindFlowViewModel(
            ResolveTargetUseCase(FakeEpcLookup()),
            FindTagUseCase(FakeRfidReader(KNOWN_EPC))
        )

        val recorded = mutableListOf<FindUiState>()
        val collectJob = collectorScope.launch { viewModel.state.collect { recorded.add(it) } }

        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(1_000)
        dispatcher.scheduler.runCurrent()

        collectJob.cancel()
        collectorScope.cancel()

        val navigatingStates = recorded.filterIsInstance<FindUiState.Navigating>()
        val searchingIndex = navigatingStates.indexOfFirst { it.compass is CompassUiState.Searching }
        val firstRealReadingIndex = navigatingStates.indexOfFirst {
            it.compass is CompassUiState.Guiding || it.compass is CompassUiState.NoSignal
        }

        assertTrue(
            "expected a Navigating(Searching, ...) state to be recorded; recorded=$recorded",
            searchingIndex >= 0
        )
        assertTrue(
            "expected a Navigating(Guiding/NoSignal, ...) state to be recorded; recorded=$recorded",
            firstRealReadingIndex >= 0
        )
        assertTrue(
            "expected Searching before the first real compass state; recorded=$recorded",
            searchingIndex < firstRealReadingIndex
        )
    }
}
