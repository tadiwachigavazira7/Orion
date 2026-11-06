package com.orion.core.session

import com.orion.core.navigation.NavigationState
import com.orion.core.rfid.ReaderStatus
import com.orion.core.rfid.RfidObservation
import com.orion.core.rfid.RfidReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPC = "30245BFB8386AA80000186A1"

/** Counts every lifecycle call; failures are injectable. Shared with the ViewModel lifecycle tests. */
internal class CountingRfidReader : RfidReader {
    val feed = MutableSharedFlow<RfidObservation>(extraBufferCapacity = 16)
    val statusFlow = MutableStateFlow<ReaderStatus>(ReaderStatus.Connected)
    override val observations: Flow<RfidObservation> = feed
    override val status: Flow<ReaderStatus> = statusFlow
    var connects = 0
    var starts = 0
    var stops = 0
    var disconnects = 0
    var connectFailure: Throwable? = null
    var startFailure: Throwable? = null
    var stopFailure: Throwable? = null
    var disconnectFailure: Throwable? = null

    override suspend fun connect(): Result<Unit> {
        connects++
        return connectFailure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun startInventory(): Result<Unit> {
        starts++
        return startFailure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun stopInventory(): Result<Unit> {
        stops++
        return stopFailure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun disconnect() {
        disconnects++
        disconnectFailure?.let { throw it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FindTagUseCaseLifecycleTest {

    private fun TestScope.useCase(reader: RfidReader, failures: MutableList<Throwable> = mutableListOf()) =
        FindTagUseCase(
            reader,
            clock = { testScheduler.currentTime },
            signalTimeoutMs = 3000,
            onTeardownFailure = { failures += it }
        )

    private fun TestScope.obs(rssi: Double, epc: String = EPC) =
        RfidObservation(epc, "r", rssi, timestamp = testScheduler.currentTime)

    @Test
    fun `cancelling the collector stops inventory and disconnects exactly once`() = runTest {
        val reader = CountingRfidReader()
        val job = launch { useCase(reader).find(EPC).collect { } }
        runCurrent()

        job.cancel()
        runCurrent()

        assertEquals(1, reader.connects)
        assertEquals(1, reader.starts)
        assertEquals(1, reader.stops)
        assertEquals(1, reader.disconnects)
    }

    @Test
    fun `warm-up then find connects and starts once, and release is idempotent`() = runTest {
        val reader = CountingRfidReader()
        val useCase = useCase(reader)
        assertTrue(useCase.warmUp().isSuccess)

        val job = launch { useCase.find(EPC).collect { } }
        runCurrent()
        job.cancel()
        runCurrent()
        useCase.release()

        assertEquals(1, reader.connects)
        assertEquals(1, reader.starts)
        assertEquals(1, reader.stops)
        assertEquals(1, reader.disconnects)
    }

    @Test
    fun `failed warm-up is not ready, leaves nothing open, and find retries`() = runTest {
        val reader = CountingRfidReader().apply { startFailure = IllegalStateException("no inventory") }
        val useCase = useCase(reader)

        assertTrue(useCase.warmUp().isFailure)
        assertEquals(1, reader.disconnects) // connected-but-not-inventorying is released

        reader.startFailure = null
        val job = launch { useCase.find(EPC).collect { } }
        runCurrent()
        assertEquals(2, reader.connects)
        assertEquals(2, reader.starts)
        job.cancel()
        runCurrent()
    }

    @Test
    fun `teardown failures are reported and do not stop the disconnect`() = runTest {
        val reader = CountingRfidReader().apply {
            stopFailure = IllegalStateException("stop failed")
            disconnectFailure = IllegalStateException("disconnect failed")
        }
        val failures = mutableListOf<Throwable>()
        val useCase = useCase(reader, failures)
        useCase.warmUp()

        val result = useCase.release()

        assertTrue(result.isFailure)
        assertEquals(1, reader.disconnects)
        assertEquals(listOf("stop failed", "disconnect failed"), failures.map { it.message })
    }

    @Test
    fun `reader disconnect surfaces as ReaderUnavailableException, not as a signal gap`() = runTest {
        val reader = CountingRfidReader()
        var error: Throwable? = null
        launch { useCase(reader).find(EPC).catch { error = it }.collect { } }
        runCurrent()

        reader.statusFlow.value = ReaderStatus.Disconnected("cable unplugged")
        runCurrent()

        assertTrue("error=$error", error is ReaderUnavailableException)
        assertEquals(1, reader.disconnects)
    }

    @Test
    fun `a target never seen surfaces as a signal gap after the timeout`() = runTest {
        val reader = CountingRfidReader()
        val states = mutableListOf<NavigationState>()
        val job = launch { useCase(reader).interpret(EPC).collect { states += it } }
        runCurrent()
        assertTrue(states.isEmpty()) // still just "searching"

        advanceTimeBy(4_500)
        runCurrent()
        job.cancel()

        assertEquals(1, states.size)
        assertNull(states.single().proximity)
    }

    @Test
    fun `unrelated epcs and invalid rssi neither feed the engine nor reset the timer`() = runTest {
        val reader = CountingRfidReader()
        val states = mutableListOf<NavigationState>()
        val useCase = useCase(reader)
        val job = launch { useCase.interpret(EPC).collect { states += it } }
        runCurrent()

        repeat(6) {
            reader.feed.tryEmit(obs(-50.0, epc = "OTHER"))
            reader.feed.tryEmit(obs(500.0))
            runCurrent()
            advanceTimeBy(750)
            runCurrent()
        }
        job.cancel()

        assertEquals("only the gap state expected: $states", 1, states.size)
        assertNull(states.single().proximity)
        assertEquals(6, useCase.rejectedReadCount)
    }
}
