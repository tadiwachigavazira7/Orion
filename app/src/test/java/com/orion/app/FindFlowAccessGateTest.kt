package com.orion.app

import androidx.lifecycle.ViewModelStore
import com.orion.core.enrollment.DeviceAuthorizationState
import com.orion.core.enrollment.FakeDeviceAccessGate
import com.orion.core.inventory.EpcLookup
import com.orion.core.inventory.EpcTarget
import com.orion.core.inventory.FindInput
import com.orion.core.inventory.ResolveResult
import com.orion.core.inventory.ResolveTargetUseCase
import com.orion.core.rfid.RfidObservation
import com.orion.core.rfid.RfidReader
import com.orion.core.session.FindTagUseCase
import com.orion.integrations.fake.FakeEpcLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val KNOWN_EPC = "30245BFB8386AA80000186A1"

/**
 * Like a real vendor adapter: every lifecycle call suspends (delay stands in for
 * withContext(IO) / SDK callbacks) BEFORE taking effect, so a teardown that runs in an
 * already-cancelled coroutine would throw at the delay and never record the call.
 */
private class SuspendingRfidReader(private val connectDelayMs: Long = 10) : RfidReader {
    val calls = mutableListOf<String>()
    override val observations: Flow<RfidObservation> = flow {
        emit(RfidObservation(KNOWN_EPC, "suspending-reader", -60.0, timestamp = 0L))
        awaitCancellation()
    }
    override suspend fun connect(): Result<Unit> { delay(connectDelayMs); calls += "connect"; return Result.success(Unit) }
    override suspend fun startInventory(): Result<Unit> { delay(10); calls += "startInventory"; return Result.success(Unit) }
    override suspend fun stopInventory(): Result<Unit> { delay(10); calls += "stopInventory"; return Result.success(Unit) }
    override suspend fun disconnect() { delay(10); calls += "disconnect" }
}

/** Lookup that takes time, so a block can arrive while it is in flight. */
private class SlowLookup : EpcLookup {
    private val delegate = FakeEpcLookup()
    override suspend fun validate(epc: String): ResolveResult { delay(1_000); return delegate.validate(epc) }
    override suspend fun searchEpcs(query: String): List<EpcTarget> { delay(1_000); return delegate.searchEpcs(query) }
}

/** Records every lifecycle call so tests can prove the RFID session was actually torn down. */
private class RecordingRfidReader : RfidReader {
    val calls = mutableListOf<String>()
    override val observations: Flow<RfidObservation> = flow {
        emit(RfidObservation(KNOWN_EPC, "recording-reader", -60.0, timestamp = 0L))
        awaitCancellation()
    }
    override suspend fun connect(): Result<Unit> { calls += "connect"; return Result.success(Unit) }
    override suspend fun startInventory(): Result<Unit> { calls += "startInventory"; return Result.success(Unit) }
    override suspend fun stopInventory(): Result<Unit> { calls += "stopInventory"; return Result.success(Unit) }
    override suspend fun disconnect() { calls += "disconnect" }
}

private class CountingLookup : EpcLookup {
    private val delegate = FakeEpcLookup()
    var calls = 0
    override suspend fun validate(epc: String): ResolveResult { calls++; return delegate.validate(epc) }
    override suspend fun searchEpcs(query: String): List<EpcTarget> { calls++; return delegate.searchEpcs(query) }
}

/**
 * Device authorization vs. the find flow: losing access must cancel the active RFID
 * session (FindTagUseCase teardown: stopInventory + disconnect), and a blocked device
 * must not start lookups, warm-up, or navigation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FindFlowAccessGateTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    private fun advanceMain() = mainDispatcher.scheduler.advanceUntilIdle()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `explicit denial during navigation stops inventory and disconnects the reader`() {
        val reader = RecordingRfidReader()
        val gate = FakeDeviceAccessGate()
        val viewModel = FindFlowViewModel(ResolveTargetUseCase(FakeEpcLookup()), FindTagUseCase(reader), gate)
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        assertTrue(viewModel.state.value is FindUiState.Navigating)
        assertEquals(listOf("connect", "startInventory"), reader.calls)

        gate.state.value = DeviceAuthorizationState.Revoked

        assertEquals(FindUiState.AccessBlocked, viewModel.state.value)
        assertEquals(listOf("connect", "startInventory", "stopInventory", "disconnect"), reader.calls)
    }

    @Test
    fun `offline allowance expiring during navigation also tears the session down`() {
        val reader = RecordingRfidReader()
        val gate = FakeDeviceAccessGate().apply {
            state.value = DeviceAuthorizationState.OfflineAuthorized(Long.MAX_VALUE, "offline")
        }
        val viewModel = FindFlowViewModel(ResolveTargetUseCase(FakeEpcLookup()), FindTagUseCase(reader), gate)
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))

        gate.state.value = DeviceAuthorizationState.ConnectionRequired

        assertEquals(FindUiState.AccessBlocked, viewModel.state.value)
        assertTrue(reader.calls.containsAll(listOf("stopInventory", "disconnect")))
    }

    @Test
    fun `blocked device runs no lookup, warm-up, or reader connection`() {
        val reader = RecordingRfidReader()
        val lookup = CountingLookup()
        val gate = FakeDeviceAccessGate().apply { state.value = DeviceAuthorizationState.ConnectionRequired }
        val viewModel = FindFlowViewModel(ResolveTargetUseCase(lookup), FindTagUseCase(reader), gate)

        viewModel.onResolutionScreenOpened()
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        viewModel.onSearch("shoe")
        viewModel.onEpcChosen(EpcTarget(KNOWN_EPC, "Blue Running Shoe M9"))

        assertEquals(FindUiState.AccessBlocked, viewModel.state.value)
        assertEquals(0, lookup.calls)
        assertTrue("reader must stay untouched, was ${reader.calls}", reader.calls.isEmpty())
    }

    @Test
    fun `denial releases a reader that was only warmed up`() {
        val reader = RecordingRfidReader()
        val gate = FakeDeviceAccessGate()
        val viewModel = FindFlowViewModel(ResolveTargetUseCase(FakeEpcLookup()), FindTagUseCase(reader), gate)
        viewModel.onResolutionScreenOpened()
        assertEquals(listOf("connect", "startInventory"), reader.calls)

        gate.state.value = DeviceAuthorizationState.InvalidCredential

        assertEquals(listOf("connect", "startInventory", "stopInventory", "disconnect"), reader.calls)
    }

    @Test
    fun `access restored returns the flow to Idle without auto-starting navigation`() {
        val reader = RecordingRfidReader()
        val gate = FakeDeviceAccessGate()
        val viewModel = FindFlowViewModel(ResolveTargetUseCase(FakeEpcLookup()), FindTagUseCase(reader), gate)
        gate.state.value = DeviceAuthorizationState.ConnectionRequired
        assertEquals(FindUiState.AccessBlocked, viewModel.state.value)

        gate.state.value = DeviceAuthorizationState.Authorized(Long.MAX_VALUE)

        assertEquals(FindUiState.Idle, viewModel.state.value)
        assertTrue(reader.calls.isEmpty())
    }

    @Test
    fun `denial tears down a reader whose stopInventory and disconnect suspend`() {
        // Regression: without NonCancellable teardown in FindTagUseCase, the cancelled
        // navigation coroutine throws at the first delay() and the reader stays connected.
        val reader = SuspendingRfidReader()
        val gate = FakeDeviceAccessGate()
        val viewModel = FindFlowViewModel(ResolveTargetUseCase(FakeEpcLookup()), FindTagUseCase(reader), gate)
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        advanceMain()
        assertEquals(listOf("connect", "startInventory"), reader.calls)

        gate.state.value = DeviceAuthorizationState.Revoked
        advanceMain()

        assertEquals(listOf("connect", "startInventory", "stopInventory", "disconnect"), reader.calls)
    }

    @Test
    fun `denial during an in-progress warm-up releases only after the warm-up has stopped`() {
        val reader = SuspendingRfidReader(connectDelayMs = 500)
        val gate = FakeDeviceAccessGate()
        val viewModel = FindFlowViewModel(ResolveTargetUseCase(FakeEpcLookup()), FindTagUseCase(reader), gate)
        viewModel.onResolutionScreenOpened()
        mainDispatcher.scheduler.advanceTimeBy(100) // connect still in flight

        gate.state.value = DeviceAuthorizationState.InvalidCredential
        advanceMain()

        assertEquals(listOf("stopInventory", "disconnect"), reader.calls.takeLast(2))
        assertTrue("nothing reconnected after release: ${reader.calls}", !reader.calls.contains("connect"))
    }

    @Test
    fun `clearing the ViewModel releases a warmed-up reader`() {
        val reader = SuspendingRfidReader()
        val viewModel = FindFlowViewModel(ResolveTargetUseCase(FakeEpcLookup()), FindTagUseCase(reader), FakeDeviceAccessGate())
        viewModel.onResolutionScreenOpened()
        advanceMain()
        assertEquals(listOf("connect", "startInventory"), reader.calls)

        ViewModelStore().apply { put("find", viewModel); clear() }
        advanceMain()

        assertEquals(listOf("connect", "startInventory", "stopInventory", "disconnect"), reader.calls)
    }

    @Test
    fun `restarting navigation connects only after the previous session has disconnected`() {
        val reader = SuspendingRfidReader()
        val viewModel = FindFlowViewModel(ResolveTargetUseCase(FakeEpcLookup()), FindTagUseCase(reader), FakeDeviceAccessGate())
        val target = EpcTarget(KNOWN_EPC, "Blue Running Shoe M9")
        viewModel.onEpcChosen(target)
        advanceMain()

        viewModel.onEpcChosen(target)
        advanceMain()

        assertEquals(
            listOf("connect", "startInventory", "stopInventory", "disconnect", "connect", "startInventory"),
            reader.calls
        )
    }

    @Test
    fun `a lookup still in flight when access is lost cannot overwrite AccessBlocked`() {
        val reader = RecordingRfidReader()
        val gate = FakeDeviceAccessGate()
        val viewModel = FindFlowViewModel(ResolveTargetUseCase(SlowLookup()), FindTagUseCase(reader), gate)
        viewModel.onFind(FindInput.TypedEpc(KNOWN_EPC))
        viewModel.onSearch("shoe")
        assertEquals(FindUiState.Resolving, viewModel.state.value)

        gate.state.value = DeviceAuthorizationState.ConnectionRequired
        advanceMain()

        assertEquals(FindUiState.AccessBlocked, viewModel.state.value)
        assertTrue(reader.calls.isEmpty())

        gate.state.value = DeviceAuthorizationState.Authorized(Long.MAX_VALUE)
        advanceMain()
        assertEquals("restore returns to Idle, not to a stale lookup result", FindUiState.Idle, viewModel.state.value)
    }
}
