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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import com.orion.core.navigation.Trend
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

/** Test-controlled reader that counts lifecycle calls; observations are pushed by the test. */
private class ControllableRfidReader(private val connectResult: Result<Unit> = Result.success(Unit)) : RfidReader {
    val feed = MutableSharedFlow<RfidObservation>(extraBufferCapacity = 16)
    override val observations: Flow<RfidObservation> = feed
    var connects = 0
    var stops = 0
    var disconnects = 0
    override suspend fun connect(): Result<Unit> { connects++; return connectResult }
    override suspend fun startInventory() = Result.success(Unit)
    override suspend fun stopInventory(): Result<Unit> { stops++; return Result.success(Unit) }
    override suspend fun disconnect() { disconnects++ }

    fun emitRssi(rssi: Double) {
        check(feed.tryEmit(RfidObservation(KNOWN_EPC, "test-reader", rssi, timestamp = 0L)))
    }
}

/** Reader whose FIRST disconnect() suspends until [releaseFirstDisconnect] is completed; logs call order. */
private class SlowDisconnectRfidReader : RfidReader {
    val feed = MutableSharedFlow<RfidObservation>(extraBufferCapacity = 16)
    override val observations: Flow<RfidObservation> = feed
    val releaseFirstDisconnect = CompletableDeferred<Unit>()
    val log = mutableListOf<String>()
    private var disconnectCalls = 0
    override suspend fun connect(): Result<Unit> { log += "connect"; return Result.success(Unit) }
    override suspend fun startInventory(): Result<Unit> { log += "start"; return Result.success(Unit) }
    override suspend fun stopInventory(): Result<Unit> { log += "stop"; return Result.success(Unit) }
    override suspend fun disconnect() {
        log += "disconnect-begin"
        if (disconnectCalls++ == 0) releaseFirstDisconnect.await()
        log += "disconnect-end"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FindFlowViewModelTest {

    private fun viewModelWith(reader: RfidReader) = FindFlowViewModel(
        ResolveTargetUseCase(FakeEpcLookup()),
        FindTagUseCase(reader)
    )

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

    @Test
    fun `back from Searching returns to Idle, cancels the collector and releases the reader`() {
        val reader = ControllableRfidReader()
        val viewModel = viewModelWith(reader)
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        assertEquals(CompassUiState.Searching, (viewModel.state.value as FindUiState.Navigating).compass)
        assertEquals(1, reader.feed.subscriptionCount.value)

        viewModel.back()

        assertEquals(FindUiState.Idle, viewModel.state.value)
        assertEquals(0, reader.feed.subscriptionCount.value)
        assertEquals(1, reader.stops)
        assertEquals(1, reader.disconnects)
        // Late reads after back must not resurrect navigation.
        reader.emitRssi(-30.0)
        assertEquals(FindUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `back from TargetAcquired returns to Idle and releases the reader`() {
        val reader = ControllableRfidReader()
        val viewModel = viewModelWith(reader)
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        reader.emitRssi(-30.0)
        assertEquals(CompassUiState.TargetAcquired, (viewModel.state.value as FindUiState.Navigating).compass)

        viewModel.back()

        assertEquals(FindUiState.Idle, viewModel.state.value)
        assertEquals(0, reader.feed.subscriptionCount.value)
        assertEquals(1, reader.stops)
        assertEquals(1, reader.disconnects)
    }

    @Test
    fun `back from a compass Error returns to Idle and a new search works`() {
        val viewModel = viewModelWith(FailingRfidReader())
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        assertTrue((viewModel.state.value as FindUiState.Navigating).compass is CompassUiState.Error)

        viewModel.back()
        assertEquals(FindUiState.Idle, viewModel.state.value)

        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        assertTrue(viewModel.state.value is FindUiState.Navigating)
    }

    @Test
    fun `back from Guiding returns to Idle`() {
        val reader = ControllableRfidReader()
        val viewModel = viewModelWith(reader)
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        reader.emitRssi(-70.0)
        assertTrue((viewModel.state.value as FindUiState.Navigating).compass is CompassUiState.Guiding)

        viewModel.back()

        assertEquals(FindUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `back with no active navigation is a safe no-op returning Idle`() {
        // back() is only wired from the compass screen, but it must be safe (idempotent)
        // when no navigation job exists.
        val viewModel = viewModelWith(ControllableRfidReader())
        viewModel.back()
        assertEquals(FindUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `second search for the same epc after TargetAcquired starts from fresh engine state`() {
        val reader = ControllableRfidReader()
        val viewModel = viewModelWith(reader)
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        reader.emitRssi(-30.0)
        assertEquals(CompassUiState.TargetAcquired, (viewModel.state.value as FindUiState.Navigating).compass)
        viewModel.back()

        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        // New session begins in Searching, not the previous TargetAcquired.
        assertEquals(CompassUiState.Searching, (viewModel.state.value as FindUiState.Navigating).compass)
        assertEquals(2, reader.connects)
        reader.emitRssi(-75.0)

        val guiding = (viewModel.state.value as FindUiState.Navigating).compass as CompassUiState.Guiding
        // Stale smoothed RSSI (-30) would give a much higher proximity and a COLDER trend.
        assertEquals((-75.0 + 80.0) / 45.0, guiding.proximity, 1e-9)
        assertEquals(Trend.UNKNOWN, guiding.trend)
        assertEquals(1, reader.feed.subscriptionCount.value)
    }

    @Test
    fun `starting a new navigation directly cancels the previous session before connecting again`() {
        val reader = ControllableRfidReader()
        val viewModel = viewModelWith(reader)
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))

        assertEquals(1, reader.feed.subscriptionCount.value)
        assertEquals(1, reader.stops)
        assertEquals(1, reader.disconnects)
        assertEquals(2, reader.connects)
    }

    @Test
    fun `back then immediate find waits for the old session's disconnect before reconnecting`() {
        val reader = SlowDisconnectRfidReader()
        val viewModel = viewModelWith(reader)
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        assertEquals(listOf("connect", "start"), reader.log)

        viewModel.back()
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))

        // Old disconnect is still suspended: no second connect may have happened.
        assertEquals(listOf("connect", "start", "stop", "disconnect-begin"), reader.log)

        reader.releaseFirstDisconnect.complete(Unit)

        assertEquals(
            listOf("connect", "start", "stop", "disconnect-begin", "disconnect-end", "connect", "start"),
            reader.log
        )
    }

    @Test
    fun `back then warmUp waits for the old session's disconnect before reconnecting`() {
        val reader = SlowDisconnectRfidReader()
        val viewModel = viewModelWith(reader)
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))

        viewModel.back()
        viewModel.onResolutionScreenOpened()

        assertEquals(listOf("connect", "start", "stop", "disconnect-begin"), reader.log)

        reader.releaseFirstDisconnect.complete(Unit)

        assertEquals(
            listOf("connect", "start", "stop", "disconnect-begin", "disconnect-end", "connect", "start"),
            reader.log
        )
    }

    @Test
    fun `back during a pending typed-EPC resolve cancels it so it cannot resurrect Navigating`() {
        val gate = CompletableDeferred<ResolveResult>()
        val lookup = object : EpcLookup {
            override suspend fun validate(epc: String) = gate.await()
            override suspend fun searchEpcs(query: String) = emptyList<EpcTarget>()
        }
        val reader = ControllableRfidReader()
        val viewModel = FindFlowViewModel(ResolveTargetUseCase(lookup), FindTagUseCase(reader))

        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        assertEquals(FindUiState.Resolving, viewModel.state.value)
        viewModel.back()
        gate.complete(ResolveResult.Resolved(EpcTarget(KNOWN_EPC, KNOWN_DISPLAY_NAME)))

        assertEquals(FindUiState.Idle, viewModel.state.value)
        assertEquals(0, reader.connects)
    }

    @Test
    fun `back during a pending search cancels it so it cannot resurrect PickEpc`() {
        val gate = CompletableDeferred<List<EpcTarget>>()
        val lookup = object : EpcLookup {
            override suspend fun validate(epc: String) = ResolveResult.NotFound(epc)
            override suspend fun searchEpcs(query: String) = gate.await()
        }
        val viewModel = FindFlowViewModel(ResolveTargetUseCase(lookup), FindTagUseCase(ControllableRfidReader()))

        viewModel.onSearch("shoe")
        viewModel.back()
        gate.complete(listOf(EpcTarget(KNOWN_EPC, KNOWN_DISPLAY_NAME)))

        assertEquals(FindUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `a newer resolve replaces an older pending one`() {
        val first = CompletableDeferred<ResolveResult>()
        var calls = 0
        val lookup = object : EpcLookup {
            override suspend fun validate(epc: String): ResolveResult =
                if (calls++ == 0) first.await() else ResolveResult.NotFound(epc)
            override suspend fun searchEpcs(query: String) = emptyList<EpcTarget>()
        }
        val reader = ControllableRfidReader()
        val viewModel = FindFlowViewModel(ResolveTargetUseCase(lookup), FindTagUseCase(reader))

        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        first.complete(ResolveResult.Resolved(EpcTarget(KNOWN_EPC, null)))

        assertTrue(viewModel.state.value is FindUiState.NotFound)
        assertEquals(0, reader.connects)
    }

    @Test
    fun `chain A back B back C connects only after A's disconnect finishes and B never connects`() {
        val reader = SlowDisconnectRfidReader()
        val viewModel = viewModelWith(reader)
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))   // A
        viewModel.back()
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))   // B, blocked joining A
        viewModel.back()
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))   // C

        // A's disconnect still suspended: neither B nor C may have connected.
        assertEquals(listOf("connect", "start", "stop", "disconnect-begin"), reader.log)

        reader.releaseFirstDisconnect.complete(Unit)

        assertEquals(
            listOf("connect", "start", "stop", "disconnect-begin", "disconnect-end", "connect", "start"),
            reader.log
        )
        assertEquals(1, reader.feed.subscriptionCount.value)
    }
}
