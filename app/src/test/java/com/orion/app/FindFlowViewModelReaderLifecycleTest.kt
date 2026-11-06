package com.orion.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.orion.core.inventory.FindInput
import com.orion.core.inventory.ResolveTargetUseCase
import com.orion.core.navigation.Trend
import com.orion.core.rfid.ReaderStatus
import com.orion.core.rfid.RfidObservation
import com.orion.core.session.CountingRfidReader
import com.orion.core.session.FindTagUseCase
import com.orion.integrations.fake.FakeEpcLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val KNOWN_EPC = "30245BFB8386AA80000186A1"

@OptIn(ExperimentalCoroutinesApi::class)
class FindFlowViewModelReaderLifecycleTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(reader: CountingRfidReader) = FindFlowViewModel(
        ResolveTargetUseCase(FakeEpcLookup()),
        FindTagUseCase(reader),
        releaseScope = CoroutineScope(UnconfinedTestDispatcher())
    )

    private fun stored(reader: CountingRfidReader): Pair<ViewModelStore, FindFlowViewModel> {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel(reader) as T
        }
        return store to ViewModelProvider(store, factory)[FindFlowViewModel::class.java]
    }

    private fun read(rssi: Double) = RfidObservation(KNOWN_EPC, "r", rssi, timestamp = 0L)

    private fun FindFlowViewModel.compass() = (state.value as FindUiState.Navigating).compass

    @Test
    fun `warm-up then navigation starts the reader once`() {
        val reader = CountingRfidReader()
        val vm = viewModel(reader)

        vm.onResolutionScreenOpened()
        vm.onFind(FindInput.TypedEpc(KNOWN_EPC))

        assertEquals(1, reader.connects)
        assertEquals(1, reader.starts)
    }

    @Test
    fun `failed warm-up is deferred, navigation retries and surfaces the real error`() {
        val reader = CountingRfidReader().apply { connectFailure = IllegalStateException("reader unavailable") }
        val vm = viewModel(reader)

        vm.onResolutionScreenOpened()
        vm.onFind(FindInput.TypedEpc(KNOWN_EPC))

        assertEquals(2, reader.connects)
        assertEquals(CompassUiState.Error("reader unavailable"), vm.compass())
    }

    @Test
    fun `cancel releases the reader once and returns to the entry screen`() {
        val reader = CountingRfidReader()
        val vm = viewModel(reader)
        vm.onFind(FindInput.TypedEpc(KNOWN_EPC))

        vm.onCancel()

        assertEquals(FindUiState.Idle, vm.state.value)
        assertEquals(1, reader.stops)
        assertEquals(1, reader.disconnects)
    }

    @Test
    fun `backgrounding releases the reader and lands in Error, then retry resumes`() {
        val reader = CountingRfidReader()
        val vm = viewModel(reader)
        vm.onFind(FindInput.TypedEpc(KNOWN_EPC))

        vm.onAppStopped()

        assertTrue(vm.compass() is CompassUiState.Error)
        assertEquals(1, reader.disconnects)

        vm.onRetry()
        assertEquals(2, reader.connects)
        assertEquals(CompassUiState.Searching, vm.compass())
        reader.feed.tryEmit(read(-60.0))
        assertTrue(vm.compass() is CompassUiState.Guiding)
    }

    @Test
    fun `clearing the ViewModel releases a warmed but never-used reader`() {
        val reader = CountingRfidReader()
        val (store, vm) = stored(reader)
        vm.onResolutionScreenOpened()
        assertEquals(1, reader.starts)

        store.clear()

        assertEquals(1, reader.stops)
        assertEquals(1, reader.disconnects)
    }

    @Test
    fun `clearing the ViewModel mid-navigation releases the reader exactly once`() {
        val reader = CountingRfidReader()
        val (store, vm) = stored(reader)
        vm.onFind(FindInput.TypedEpc(KNOWN_EPC))

        store.clear()

        assertEquals(1, reader.stops)
        assertEquals(1, reader.disconnects)
    }

    @Test
    fun `reader disconnect mid-search is an Error, distinct from NoSignal, and releases the reader`() {
        val reader = CountingRfidReader()
        val vm = viewModel(reader)
        vm.onFind(FindInput.TypedEpc(KNOWN_EPC))

        reader.statusFlow.value = ReaderStatus.Disconnected("cable unplugged")

        val compass = vm.compass()
        assertTrue("was $compass", compass is CompassUiState.Error)
        assertTrue((compass as CompassUiState.Error).message.contains("cable unplugged"))
        assertEquals(1, reader.disconnects)
    }

    @Test
    fun `repeating the same search starts with a clean engine`() {
        val reader = CountingRfidReader()
        val vm = viewModel(reader)
        vm.onFind(FindInput.TypedEpc(KNOWN_EPC))
        reader.feed.tryEmit(read(-70.0))
        reader.feed.tryEmit(read(-40.0))
        vm.onAppStopped()
        vm.onRetry()

        reader.feed.tryEmit(read(-60.0))

        val guiding = vm.compass() as CompassUiState.Guiding
        assertEquals(Trend.UNKNOWN, guiding.trend)
    }
}
