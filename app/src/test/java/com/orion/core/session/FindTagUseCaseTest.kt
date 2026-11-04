// ============================================================
// core/session/FindTagUseCaseTest.kt
// Verifies the staleness-ticker gap detection added to FindTagUseCase.interpret():
// NavigationEngine.onSignalGap() must be structurally reachable (CLAUDE.md §12) when
// the target EPC simply stops producing reads, not just before the first observation.
// ============================================================
package com.orion.core.session

import com.orion.core.navigation.NavigationState
import com.orion.core.navigation.Trend
import com.orion.core.rfid.RfidObservation
import com.orion.core.rfid.RfidReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TARGET_EPC = "30245BFB8386AA80000186A1"
private const val SIGNAL_TIMEOUT_MS = 3000L

/** Minimal controllable reader: observations are pushed by the test via [feed]. */
private class ControllableRfidReader : RfidReader {
    val feed = MutableSharedFlow<RfidObservation>(extraBufferCapacity = 16)
    override val observations: Flow<RfidObservation> = feed
    var connects = 0
    var stops = 0
    var disconnects = 0
    override suspend fun connect(): Result<Unit> { connects++; return Result.success(Unit) }
    override suspend fun startInventory(): Result<Unit> = Result.success(Unit)
    override suspend fun stopInventory(): Result<Unit> { stops++; return Result.success(Unit) }
    override suspend fun disconnect() { disconnects++ }
}

/** A gap emission is unambiguous: NavigationEngine.onSignalGap()'s exact shape. */
private fun isGapState(state: NavigationState): Boolean =
    state.proximity == null && state.confidence == 0.0 && state.trend == Trend.UNKNOWN && !state.targetAcquired

@OptIn(ExperimentalCoroutinesApi::class)
class FindTagUseCaseTest {

    /** Virtual clock tied to the TestScope's own scheduler, matching MockRfidReaderTest's convention. */
    private fun TestScope.useCase(
        reader: RfidReader,
        signalTimeoutMs: Long = SIGNAL_TIMEOUT_MS
    ) = FindTagUseCase(
        reader = reader,
        clock = { testScheduler.currentTime },
        signalTimeoutMs = signalTimeoutMs
    )

    private fun TestScope.obs(rssi: Double, epc: String = TARGET_EPC) =
        RfidObservation(epc = epc, readerId = "test-reader", rssi = rssi, timestamp = testScheduler.currentTime)

    @Test
    fun `target goes silent past the timeout eventually surfaces a signal-gap state`() = runTest {
        val reader = ControllableRfidReader()
        val useCase = useCase(reader)
        val states = mutableListOf<NavigationState>()
        val job: Job = launch { useCase.interpret(TARGET_EPC).collect { states += it } }
        runCurrent()

        reader.feed.tryEmit(obs(-60.0))
        runCurrent()

        // Past signalTimeoutMs since the only observation, with no further reads.
        advanceTimeBy(SIGNAL_TIMEOUT_MS + 1_500)
        runCurrent()
        job.cancel()

        assertTrue("expected a gap state once the target went silent; states=$states", states.any(::isGapState))
    }

    @Test
    fun `the gap state is reported once, not re-emitted on every subsequent tick`() = runTest {
        val reader = ControllableRfidReader()
        val useCase = useCase(reader)
        val states = mutableListOf<NavigationState>()
        val job: Job = launch { useCase.interpret(TARGET_EPC).collect { states += it } }
        runCurrent()

        reader.feed.tryEmit(obs(-60.0))
        runCurrent()

        // Advance well past the timeout across many tick intervals with no further reads.
        advanceTimeBy(SIGNAL_TIMEOUT_MS * 5)
        runCurrent()
        job.cancel()

        val gapCount = states.count(::isGapState)
        assertEquals("the gap state must be emitted exactly once while the gap continues, not flooded", 1, gapCount)
    }

    @Test
    fun `target acquired then goes silent eventually loses targetAcquired`() = runTest {
        val reader = ControllableRfidReader()
        val useCase = useCase(reader)
        val states = mutableListOf<NavigationState>()
        val job: Job = launch { useCase.interpret(TARGET_EPC).collect { states += it } }
        runCurrent()

        // Strong enough to cross NavigationEngine's default acquisition threshold (-35.0).
        reader.feed.tryEmit(obs(-20.0))
        runCurrent()
        assertTrue("expected TargetAcquired before going silent", states.last().targetAcquired)

        advanceTimeBy(SIGNAL_TIMEOUT_MS + 1_500)
        runCurrent()
        job.cancel()

        assertFalse(
            "UI must not remain permanently in Target Found once the target's signal is lost",
            states.last().targetAcquired
        )
    }

    @Test
    fun `reacquiring after a gap resumes navigation and preserves smoothing across the gap`() = runTest {
        val reader = ControllableRfidReader()
        val useCase = useCase(reader)
        val states = mutableListOf<NavigationState>()
        val job: Job = launch { useCase.interpret(TARGET_EPC).collect { states += it } }
        runCurrent()

        reader.feed.tryEmit(obs(-70.0)) // first observation: smoothedRssi=-70, lastSmoothed=null
        runCurrent()
        reader.feed.tryEmit(obs(-65.0)) // smoothedRssi=-68.5, lastSmoothed=-70 -> HOTTER
        runCurrent()
        assertEquals(Trend.HOTTER, states.last().trend)

        // Gap: no reads for longer than the timeout.
        advanceTimeBy(SIGNAL_TIMEOUT_MS + 1_500)
        runCurrent()
        assertTrue("expected the gap to be detected", states.any(::isGapState))

        // Reacquire: navigation must resume (not stuck in the gap state forever).
        reader.feed.tryEmit(obs(-60.0))
        runCurrent()
        job.cancel()

        val resumed = states.last()
        // Intentional contract: the gap path does NOT call engine.reset(), so the engine's
        // smoothedRssi from before the gap survives. The post-gap observation is therefore
        // NOT treated as the "first" observation (which would force Trend.UNKNOWN) — its
        // trend is computed against the preserved pre-gap smoothed value. This is the
        // documented "preserve smoothing across a brief gap" decision.
        assertEquals(
            "post-gap trend should resume using the pre-gap smoothed history, not restart as UNKNOWN",
            Trend.HOTTER,
            resumed.trend
        )
        assertFalse(isGapState(resumed))
    }

    @Test
    fun `a continuous ramp with no gap never spuriously enters the gap state`() = runTest {
        val reader = ControllableRfidReader()
        val useCase = useCase(reader)
        val states = mutableListOf<NavigationState>()
        val job: Job = launch { useCase.interpret(TARGET_EPC).collect { states += it } }
        runCurrent()

        // Reads well within the timeout window (every 500ms, timeout is 3000ms).
        var rssi = -75.0
        repeat(14) {
            reader.feed.tryEmit(obs(rssi))
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
            rssi += 3.0
        }
        job.cancel()

        assertTrue(states.isNotEmpty())
        assertFalse(
            "a continuous ramp must never spuriously surface the signal-gap state; states=$states",
            states.any(::isGapState)
        )
    }
}
